package net.loyalnetwork.coffeelib.config;

import net.loyalnetwork.coffeelib.config.annotation.*;
import net.loyalnetwork.coffeelib.config.file.ConfigEntry;
import net.loyalnetwork.coffeelib.config.file.ConfigFileHandle;
import net.loyalnetwork.coffeelib.config.node.ConfigValue;
import net.loyalnetwork.coffeelib.config.serializer.*;
import net.loyalnetwork.coffeelib.config.validation.RangeValidator;
import net.loyalnetwork.coffeelib.config.validation.ValidationException;
import org.slf4j.Logger;

import net.loyalnetwork.coffeelib.config.reload.WatchServiceManager;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.*;

public final class ConfigManager {

    private final Logger logger;
    private final File configDir;
    private final List<Class<?>> configClasses;
    private final Map<Class<?>, List<FieldData>> fieldsByClass;
    private final Map<Class<?>, List<Method>> reloadMethods;
    private final List<ConfigFileHandle> handles;
    private WatchServiceManager watcher;
    private ScheduledExecutorService debouncer;
    private ScheduledFuture<?> debounceTask;
    private boolean shutdownHookRegistered;

    private ConfigManager(Logger logger, File configDir, List<Class<?>> configClasses) {
        this.logger = logger;
        this.configDir = configDir;
        this.configClasses = List.copyOf(configClasses);
        this.fieldsByClass = new LinkedHashMap<>();
        this.reloadMethods = new LinkedHashMap<>();
        this.handles = new ArrayList<>();
    }

    public static Builder builder(Logger logger, File configDir) {
        return new Builder(logger, configDir);
    }

    public void load() {
        ConfigBootstrap.registerDefaults();
        fieldsByClass.clear();
        reloadMethods.clear();
        handles.clear();

        for (Class<?> configClass : configClasses) {
            loadClass(configClass);
        }

        startWatching();
    }

    public void close() {
        stopWatching();
    }

    public void reload() {
        for (ConfigFileHandle handle : handles) {
            handle.reload();
        }
        for (var entry : fieldsByClass.entrySet()) {
            for (FieldData data : entry.getValue()) {
                ConfigValue<?> configValue = data.configValue();
                if (configValue.isBound()) {
                    configValue.reload();
                }
            }
        }
        for (var entry : reloadMethods.entrySet()) {
            for (Method method : entry.getValue()) {
                try {
                    method.setAccessible(true);
                    method.invoke(null);
                } catch (Exception e) {
                    logger.warn("Failed to invoke @ReloadListener: {}", method, e);
                }
            }
        }
    }

    private void startWatching() {
        stopWatching();
        debouncer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Config-Debounce");
            t.setDaemon(true);
            return t;
        });
        watcher = new WatchServiceManager();
        try {
            watcher.start(configDir.toPath(), () -> {
                if (debounceTask != null) {
                    debounceTask.cancel(false);
                }
                debounceTask = debouncer.schedule(ConfigManager.this::reload, 100, TimeUnit.MILLISECONDS);
            });
            registerShutdownHook();
            logger.info("File watching started for {}", configDir);
        } catch (Exception e) {
            logger.warn("Could not start file watching for {}: {}", configDir, e.getMessage());
            watcher = null;
        }
    }

    private void stopWatching() {
        if (debounceTask != null) {
            debounceTask.cancel(false);
            debounceTask = null;
        }
        if (debouncer != null) {
            debouncer.shutdownNow();
            debouncer = null;
        }
        if (watcher != null) {
            watcher.close();
            watcher = null;
        }
    }

    private void registerShutdownHook() {
        if (shutdownHookRegistered) return;
        shutdownHookRegistered = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stopWatching();
            logger.info("File watching stopped for {}", configDir);
        }));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void loadClass(Class<?> configClass) {
        ConfigFile configFile = configClass.getAnnotation(ConfigFile.class);
        if (configFile == null) {
            logger.warn("Skipping {}: missing @ConfigFile annotation", configClass);
            return;
        }

        String fileName = configFile.value();
        List<FieldData> fieldDataList = new ArrayList<>();
        List<Method> methods = new ArrayList<>();

        for (Field field : configClass.getDeclaredFields()) {
            if (!ConfigValue.class.isAssignableFrom(field.getType())) {
                continue;
            }
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }

            field.setAccessible(true);
            ConfigValue<?> configValue;
            try {
                configValue = (ConfigValue<?>) field.get(null);
            } catch (IllegalAccessException e) {
                logger.warn("Cannot access field {}: {}", field, e.getMessage());
                continue;
            }

            if (configValue == null) {
                continue;
            }

            String path = resolvePath(field);
            String[] comments = resolveComments(field);
            Range range = field.getAnnotation(Range.class);
            SerializeWith serializeWith = field.getAnnotation(SerializeWith.class);

            Class<?> valueType = resolveValueType(field);
            ConfigSerializer serializer = resolveSerializer(valueType, serializeWith);

            Object serializedDefault = configValue.defaultValue();
            if (serializer != null) {
                serializedDefault = serializer.serialize(configValue.defaultValue());
            }

            fieldDataList.add(new FieldData(
                    field,
                    configValue,
                    path,
                    comments,
                    range,
                    serializer,
                    valueType,
                    serializedDefault
            ));
        }

        for (Method method : configClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(ReloadListener.class)
                    && java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                methods.add(method);
            }
        }

        if (fieldDataList.isEmpty()) {
            logger.warn("No ConfigValue fields found in {}", configClass);
            return;
        }

        List<ConfigEntry> entries = fieldDataList.stream()
                .flatMap(data -> flattenEntries(data.path(), data.serializedDefault(), data.comments()).stream())
                .toList();

        ConfigFileHandle handle = new ConfigFileHandle(
                configDir,
                fileName,
                entries
        );
        handle.load();

        fieldsByClass.put(configClass, fieldDataList);
        handles.add(handle);

        if (!methods.isEmpty()) {
            reloadMethods.put(configClass, methods);
        }

        for (FieldData data : fieldDataList) {
            Object rawValue = rawValueForKey(handle, data);
            Object value;

            if (rawValue != null && data.serializer() != null) {
                value = data.serializer().deserialize(rawValue);
            } else if (rawValue != null) {
                value = rawValue;
            } else {
                value = data.configValue().defaultValue();
            }

            if (data.range() != null && value instanceof Number number) {
                RangeValidator validator = new RangeValidator(
                        data.range().min(),
                        data.range().max()
                );
                try {
                    validator.validate(number, data.path(), fileName);
                } catch (ValidationException e) {
                    logger.warn(e.getMessage());
                }
            }

            ConfigValue rawCv = data.configValue();
            if (data.serializer() != null) {
                rawCv.bind(
                        data.path(),
                        handle.config(),
                        data.serializer()
                );
            } else {
                rawCv.bind(data.path(), handle.config());
            }
        }
    }

    private String resolvePath(Field field) {
        Key key = field.getAnnotation(Key.class);
        if (key != null) {
            return key.value();
        }
        return field.getName()
                .toLowerCase(Locale.ROOT)
                .replace('_', '-');
    }

    private String[] resolveComments(Field field) {
        Comment comment = field.getAnnotation(Comment.class);
        return comment != null ? comment.value() : new String[0];
    }

    private Class<?> resolveValueType(Field field) {
        Type genericType = field.getGenericType();
        if (genericType instanceof ParameterizedType paramType) {
            Type[] typeArgs = paramType.getActualTypeArguments();
            if (typeArgs.length > 0 && typeArgs[0] instanceof Class<?> typeClass) {
                return typeClass;
            }
        }
        return Object.class;
    }

    @SuppressWarnings("unchecked")
    private <T> ConfigSerializer<T> resolveSerializer(
            Class<T> valueType,
            SerializeWith annotation
    ) {
        if (annotation != null) {
            try {
                Class<? extends ConfigSerializer<?>> serializerClass = annotation.value();
                if (EnumSerializer.class.isAssignableFrom(serializerClass) && valueType.isEnum()) {
                    return (ConfigSerializer<T>) new EnumSerializer<>((Class<? extends Enum>) valueType);
                }
                if (RecordSerializer.class.isAssignableFrom(serializerClass) && valueType.isRecord()) {
                    return (ConfigSerializer<T>) new RecordSerializer((Class<? extends Record>) valueType);
                }
                return (ConfigSerializer<T>) serializerClass.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException("Failed to instantiate custom serializer for " + valueType, e);
            }
        }

        ConfigSerializer<T> registered = SerializerRegistry.find(valueType);
        if (registered != null) {
            return registered;
        }

        if (valueType.isEnum()) {
            return (ConfigSerializer<T>) new EnumSerializer<>((Class<? extends Enum>) valueType);
        }

        if (valueType.isRecord()) {
            return (ConfigSerializer<T>) new RecordSerializer((Class<? extends Record>) valueType);
        }

        return null;
    }

    private List<ConfigEntry> flattenEntries(String prefix, Object serialized, String[] comments) {
        if (serialized instanceof Map<?, ?> map) {
            List<ConfigEntry> result = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String childPath = prefix + "." + entry.getKey();
                result.addAll(flattenEntries(childPath, entry.getValue(), null));
            }
            return result;
        }
        return List.of(new ConfigEntry(prefix, serialized, comments));
    }

    private Object rawValueForKey(ConfigFileHandle handle, FieldData data) {
        if (data.serializer() != null && data.valueType().isRecord()) {
            return handle.config().getSection(data.path()); // can be null; RecordSerializer/ConfigValue handle null
        }
        return handle.config().get(data.path());
    }

    private record FieldData(
            Field field,
            ConfigValue<?> configValue,
            String path,
            String[] comments,
            Range range,
            ConfigSerializer<?> serializer,
            Class<?> valueType,
            Object serializedDefault
    ) {
    }

    public static final class Builder {

        private final Logger logger;
        private final File configDir;
        private final List<Class<?>> classes = new ArrayList<>();

        private Builder(Logger logger, File configDir) {
            this.logger = logger;
            this.configDir = configDir;
        }

        public Builder register(Class<?> configClass) {
            classes.add(configClass);
            return this;
        }

        public ConfigManager build() {
            return new ConfigManager(logger, configDir, classes);
        }
    }
}
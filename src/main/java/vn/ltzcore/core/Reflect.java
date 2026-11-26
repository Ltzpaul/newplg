package vn.ltzcore.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;

/** Small compatibility layer: keeps the core independent from Paper classes at compile time. */
public final class Reflect {
    private Reflect() {}

    public static Class<?> type(String name) {
        try { return Class.forName(name); }
        catch (ClassNotFoundException e) { throw new IllegalStateException("Missing server API class: " + name, e); }
    }

    public static Object call(Object target, String name, Object... args) {
        if (target == null) return null;
        return invoke(findMethod(target.getClass(), name, args), target, args);
    }

    public static Object callStatic(String className, String name, Object... args) {
        Class<?> cls = type(className);
        return invoke(findMethod(cls, name, args), null, args);
    }

    public static Object construct(String className, Object... args) {
        Class<?> cls = type(className);
        for (Constructor<?> c : cls.getConstructors()) {
            if (matches(c.getParameterTypes(), args)) {
                try { return c.newInstance(args); }
                catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot construct " + className, e); }
            }
        }
        throw new IllegalStateException("No compatible constructor " + className + Arrays.toString(args));
    }

    private static Object invoke(Method method, Object target, Object[] args) {
        try { return method.invoke(target, args); }
        catch (ReflectiveOperationException e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite ? ite.getTargetException() : e;
            throw new IllegalStateException("Server API call failed: " + method.getDeclaringClass().getName() + "." + method.getName(), cause);
        }
    }

    private static Method findMethod(Class<?> cls, String name, Object[] args) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && matches(m.getParameterTypes(), args)) {
                    try { m.setAccessible(true); } catch (RuntimeException ignored) {}
                    return m;
                }
            }
        }
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name) && matches(m.getParameterTypes(), args)) return m;
        }
        throw new IllegalStateException("No compatible method " + cls.getName() + "." + name + Arrays.toString(args));
    }

    private static boolean matches(Class<?>[] parameters, Object[] args) {
        if (parameters.length != args.length) return false;
        for (int i = 0; i < parameters.length; i++) {
            if (args[i] == null) {
                if (parameters[i].isPrimitive()) return false;
                continue;
            }
            Class<?> p = wrap(parameters[i]);
            if (!p.isAssignableFrom(args[i].getClass())) return false;
        }
        return true;
    }

    private static Class<?> wrap(Class<?> c) {
        if (!c.isPrimitive()) return c;
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == boolean.class) return Boolean.class;
        if (c == double.class) return Double.class;
        if (c == float.class) return Float.class;
        if (c == short.class) return Short.class;
        if (c == byte.class) return Byte.class;
        if (c == char.class) return Character.class;
        return c;
    }

    public static Object enumValue(String className, String value) {
        @SuppressWarnings({"rawtypes", "unchecked"})
        Object result = Enum.valueOf((Class<? extends Enum>) type(className), value);
        return result;
    }

    public static Object fieldValue(String className, String fieldName) {
        try { return type(className).getField(fieldName).get(null); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Missing API constant " + className + "." + fieldName, e); }
    }

    public static void scheduleRepeating(Object plugin, Runnable task, long delay, long period) {
        Object scheduler = callStatic("org.bukkit.Bukkit", "getScheduler");
        call(scheduler, "runTaskTimer", plugin, task, delay, period);
    }

    public static void scheduleLater(Object plugin, Runnable task, long delay) {
        Object scheduler = callStatic("org.bukkit.Bukkit", "getScheduler");
        call(scheduler, "runTaskLater", plugin, task, delay);
    }

    public static void registerEvent(Object plugin, String eventClassName, java.util.function.Consumer<Object> consumer) {
        registerEvent(plugin, eventClassName, consumer, "NORMAL", true);
    }

    public static void registerEvent(Object plugin, String eventClassName, java.util.function.Consumer<Object> consumer,
                                     String priorityName, boolean ignoreCancelled) {
        try {
            Class<?> listenerClass = type("org.bukkit.event.Listener");
            Class<?> executorClass = type("org.bukkit.plugin.EventExecutor");
            Class<?> priorityClass = type("org.bukkit.event.EventPriority");
            Class<?> pluginClass = type("org.bukkit.plugin.Plugin");
            Object listener = Proxy.newProxyInstance(plugin.getClass().getClassLoader(), new Class<?>[]{listenerClass},
                    (proxy, method, args) -> objectMethod(proxy, method.getName(), args));
            Object executor = Proxy.newProxyInstance(plugin.getClass().getClassLoader(), new Class<?>[]{executorClass},
                    (proxy, method, args) -> {
                        if (method.getName().equals("execute") && args != null && args.length == 2) consumer.accept(args[1]);
                        return null;
                    });
            Object manager = callStatic("org.bukkit.Bukkit", "getPluginManager");
            Object priority = enumValue("org.bukkit.event.EventPriority", priorityName);
            Method register = manager.getClass().getMethod("registerEvent", Class.class, listenerClass, priorityClass,
                    executorClass, pluginClass, boolean.class);
            register.invoke(manager, type(eventClassName), listener, priority, executor, plugin, ignoreCancelled);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot register event " + eventClassName, e);
        }
    }

    private static Object objectMethod(Object proxy, String name, Object[] args) {
        return switch (name) {
            case "toString" -> "LTZCoreListener";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null ? null : args[0]);
            default -> null;
        };
    }
}

package com.agentplatform.core.plugin.classloader;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Set;

/**
 * 插件类加载器（命名空间 ClassLoader 隔离，代理模式）。
 * <p>
 * 每个插件一个独立 {@link URLClassLoader} 子加载器，实现类隔离与热卸载：
 * <ul>
 *   <li>核心类（java.* 与 javax.* 与 org.springframework.* 等）委派父加载器</li>
 *   <li>非导出包隔离（防止插件间类冲突）</li>
 *   <li>插件私有类由本加载器加载</li>
 * </ul>
 * </p>
 */
public class PluginClassLoader extends URLClassLoader {

    /** 核心包前缀（委派父加载器）。 */
    private static final String[] CORE_PREFIXES = {
            "java.", "javax.", "jakarta.", "org.slf4j.", "com.agentplatform.plugin.sdk.",
            "com.fasterxml.jackson.", "org.springframework."
    };

    private final String pluginId;
    private final Set<String> exportedPackages;

    public PluginClassLoader(String pluginId, URL[] urls, ClassLoader parent, Set<String> exportedPackages) {
        super(urls, parent);
        this.pluginId = pluginId;
        this.exportedPackages = exportedPackages;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // 已加载则直接返回
        synchronized (getClassLoadingLock(name)) {
            Class<?> cached = findLoadedClass(name);
            if (cached != null) {
                if (resolve) {
                    resolveClass(cached);
                }
                return cached;
            }

            // 核心类 / 导出包：委派父加载器
            if (isCoreClass(name) || isExported(name)) {
                return super.loadClass(name, resolve);
            }

            // 插件私有类：先本加载器，再回退父加载器
            try {
                Class<?> cls = findClass(name);
                if (resolve) {
                    resolveClass(cls);
                }
                return cls;
            } catch (ClassNotFoundException e) {
                return super.loadClass(name, resolve);
            }
        }
    }

    private boolean isCoreClass(String name) {
        for (String prefix : CORE_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isExported(String name) {
        if (exportedPackages == null || exportedPackages.isEmpty()) {
            return false;
        }
        return exportedPackages.stream().anyMatch(name::startsWith);
    }

    public String pluginId() {
        return pluginId;
    }
}
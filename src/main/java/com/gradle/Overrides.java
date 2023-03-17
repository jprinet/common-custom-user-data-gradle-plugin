package com.gradle;

import com.gradle.enterprise.gradleplugin.GradleEnterpriseBuildCache;
import com.gradle.enterprise.gradleplugin.GradleEnterpriseExtension;
import com.gradle.scan.plugin.BuildScanExtension;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.caching.configuration.BuildCacheConfiguration;
import org.gradle.caching.http.HttpBuildCache;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Optional;

import static com.gradle.Utils.prependAndAppendIfMissing;

/**
 * Provide standardized Gradle Enterprise configuration. By applying the plugin, these settings will automatically be applied.
 */
final class Overrides {

    // system properties to override Gradle Enterprise configuration
    static final String GRADLE_ENTERPRISE_URL = "gradle.enterprise.url";
    static final String GRADLE_ENTERPRISE_ALLOW_UNTRUSTED_SERVER = "gradle.enterprise.allowUntrustedServer";

    // system properties to override local build cache configuration
    static final String LOCAL_CACHE_DIRECTORY = "gradle.cache.local.directory";
    static final String LOCAL_CACHE_REMOVE_UNUSED_ENTRIES_AFTER_DAYS = "gradle.cache.local.removeUnusedEntriesAfterDays";
    static final String LOCAL_CACHE_ENABLED = "gradle.cache.local.enabled";
    static final String LOCAL_CACHE_PUSH = "gradle.cache.local.push";

    // system properties to override remote build cache configuration
    static final String REMOTE_CACHE_URL = "gradle.cache.remote.url";
    static final String REMOTE_CACHE_PATH = "gradle.cache.remote.path";
    static final String REMOTE_CACHE_ALLOW_UNTRUSTED_SERVER = "gradle.cache.remote.allowUntrustedServer";
    static final String REMOTE_CACHE_ALLOW_INSECURE_PROTOCOL = "gradle.cache.remote.allowInsecureProtocol";
    static final String REMOTE_CACHE_ENABLED = "gradle.cache.remote.enabled";
    static final String REMOTE_CACHE_PUSH = "gradle.cache.remote.push";

    private final ProviderFactory providers;

    Overrides(ProviderFactory providers) {
        this.providers = providers;
    }

    void configureGradleEnterprise(GradleEnterpriseExtension gradleEnterprise) {
        sysPropertyOrEnvVariable(GRADLE_ENTERPRISE_URL, providers).ifPresent(gradleEnterprise::setServer);
        booleanSysPropertyOrEnvVariable(GRADLE_ENTERPRISE_ALLOW_UNTRUSTED_SERVER, providers).ifPresent(gradleEnterprise::setAllowUntrustedServer);
    }

    void configureGradleEnterpriseOnGradle4(BuildScanExtension buildScan) {
        sysPropertyOrEnvVariable(GRADLE_ENTERPRISE_URL, providers).ifPresent(buildScan::setServer);
        booleanSysPropertyOrEnvVariable(GRADLE_ENTERPRISE_ALLOW_UNTRUSTED_SERVER, providers).ifPresent(buildScan::setAllowUntrustedServer);
    }

    @SuppressWarnings("DataFlowIssue")
    void configureBuildCache(BuildCacheConfiguration buildCache) {
        buildCache.local(local -> {
            sysPropertyOrEnvVariable(LOCAL_CACHE_DIRECTORY, providers).ifPresent(local::setDirectory);
            durationSysPropertyOrEnvVariable(LOCAL_CACHE_REMOVE_UNUSED_ENTRIES_AFTER_DAYS, providers).ifPresent(v -> local.setRemoveUnusedEntriesAfterDays((int) v.toDays()));
            booleanSysPropertyOrEnvVariable(LOCAL_CACHE_ENABLED, providers).ifPresent(local::setEnabled);
            booleanSysPropertyOrEnvVariable(LOCAL_CACHE_PUSH, providers).ifPresent(local::setPush);
        });

        // Only touch remote build cache configuration if it is already present and of type HttpBuildCache or GradleEnterpriseBuildCache
        // Do nothing in case of another build cache type like AWS S3 being used
        if (buildCache.getRemote() instanceof HttpBuildCache) {
            buildCache.remote(HttpBuildCache.class, remote -> {
                sysPropertyOrEnvVariable(REMOTE_CACHE_URL, providers).ifPresent(remote::setUrl);
                sysPropertyOrEnvVariable(REMOTE_CACHE_PATH, providers).map(path -> replacePath(remote.getUrl(), path)).ifPresent(remote::setUrl);
                booleanSysPropertyOrEnvVariable(REMOTE_CACHE_ALLOW_UNTRUSTED_SERVER, providers).ifPresent(remote::setAllowUntrustedServer);
                booleanSysPropertyOrEnvVariable(REMOTE_CACHE_ALLOW_INSECURE_PROTOCOL, providers).ifPresent(remote::setAllowInsecureProtocol);
                booleanSysPropertyOrEnvVariable(REMOTE_CACHE_ENABLED, providers).ifPresent(remote::setEnabled);
                booleanSysPropertyOrEnvVariable(REMOTE_CACHE_PUSH, providers).ifPresent(remote::setPush);
            });
        } else if (buildCache.getRemote() instanceof GradleEnterpriseBuildCache) {
            buildCache.remote(GradleEnterpriseBuildCache.class, remote -> {
                sysPropertyOrEnvVariable(REMOTE_CACHE_URL, providers).map(Overrides::serverOnly).ifPresent(remote::setServer);
                sysPropertyOrEnvVariable(REMOTE_CACHE_URL, providers).map(Overrides::pathOnly).ifPresent(remote::setPath);
                sysPropertyOrEnvVariable(REMOTE_CACHE_PATH, providers).ifPresent(remote::setPath);
                booleanSysPropertyOrEnvVariable(REMOTE_CACHE_ALLOW_UNTRUSTED_SERVER, providers).ifPresent(remote::setAllowUntrustedServer);
                booleanSysPropertyOrEnvVariable(REMOTE_CACHE_ALLOW_INSECURE_PROTOCOL, providers).ifPresent(remote::setAllowInsecureProtocol);
                booleanSysPropertyOrEnvVariable(REMOTE_CACHE_ENABLED, providers).ifPresent(remote::setEnabled);
                booleanSysPropertyOrEnvVariable(REMOTE_CACHE_PUSH, providers).ifPresent(remote::setPush);
            });
        }
    }

    static Optional<String> sysPropertyOrEnvVariable(String sysPropertyName, ProviderFactory providers) {
        return Utils.sysPropertyOrEnvVariable(sysPropertyName, toEnvVarName(sysPropertyName), providers);
    }

    static Optional<Boolean> booleanSysPropertyOrEnvVariable(String sysPropertyName, ProviderFactory providers) {
        return Utils.booleanSysPropertyOrEnvVariable(sysPropertyName, toEnvVarName(sysPropertyName), providers);
    }

    static Optional<Duration> durationSysPropertyOrEnvVariable(String sysPropertyName, ProviderFactory providers) {
        return Utils.durationSysPropertyOrEnvVariable(sysPropertyName, toEnvVarName(sysPropertyName), providers);
    }

    private static String toEnvVarName(String sysPropertyName) {
        return sysPropertyName.toUpperCase().replace('.', '_');
    }

    private static URI replacePath(URI uri, String path) {
        try {
            String finalPath = prependAndAppendIfMissing(path, '/');
            return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(), finalPath, uri.getQuery(), uri.getFragment());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Cannot construct URI: " + uri, e);
        }
    }

    private static String serverOnly(String urlString) {
        URI uri = URI.create(urlString);
        try {
            return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(), null, null, null).toString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Cannot construct URI: " + uri, e);
        }
    }

    private static String pathOnly(String urlString) {
        URI uri = URI.create(urlString);
        return uri.getPath();
    }

}

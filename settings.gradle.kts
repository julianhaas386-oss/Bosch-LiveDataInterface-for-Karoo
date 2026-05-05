pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GPR_USER")).get()
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GPR_KEY")).get()
            }
        }
    }
}

rootProject.name = "Bosch-LiveDataInterface-for-Karoo"
include("app")

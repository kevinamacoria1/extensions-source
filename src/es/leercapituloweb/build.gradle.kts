import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "LeerCapitulo Web"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"

    source {
        lang = "es"
        baseUrl = "https://www.leercapitulo.co"
    }
}

dependencies {
    implementation(project(":lib:synchrony"))
}

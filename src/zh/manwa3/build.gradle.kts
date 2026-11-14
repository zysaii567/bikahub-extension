import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Manwa3"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        name = "漫蛙3"
        lang = "zh"
        baseUrl {
            custom("https://manwadz.cc")
        }
    }

    deeplink {
        path("/comic/..*")
    }
}

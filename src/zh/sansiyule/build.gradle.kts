import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Sansiyule"
    versionCode = 2
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        name = "连载"
        lang = "zh"
        baseUrl {
            custom("https://www.3004gg.com")
        }
    }

    source {
        name = "完结"
        lang = "zh"
        baseUrl {
            custom("https://www.3004gg.com")
        }
    }

    deeplink {
        path("/post/..*")
    }
}

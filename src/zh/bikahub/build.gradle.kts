import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Bikahub"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        name = "哔咔漫画(bikahub)"
        lang = "zh"
        baseUrl {
            custom("https://bikahub.net")
        }
    }

    deeplink {
        path("/comic/..*")
        path("/reader/..*")
    }
}

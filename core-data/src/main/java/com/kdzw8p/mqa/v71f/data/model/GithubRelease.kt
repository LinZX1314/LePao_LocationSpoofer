package com.kdzw8p.mqa.v71f.data.model

data class GithubRelease(
    val versionName: String,
    val body: String,
    val downloadUrl: String?,
    val downloadUrl32Bit: String?,
    val publishedAt: String,
    val isPrerelease: Boolean
)

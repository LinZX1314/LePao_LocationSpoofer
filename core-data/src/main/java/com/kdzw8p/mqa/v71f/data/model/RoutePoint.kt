package com.kdzw8p.mqa.v71f.data.model

import kotlinx.serialization.Serializable

@Serializable
data class RoutePoint(val lat: Double, val lng: Double, val waitSec: Double = 0.0)

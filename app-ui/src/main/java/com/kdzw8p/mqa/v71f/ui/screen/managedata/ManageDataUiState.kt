package com.kdzw8p.mqa.v71f.ui.screen.managedata

import com.kdzw8p.mqa.v71f.data.db.CompleteLocation
import com.kdzw8p.mqa.v71f.data.db.CompleteCollectionRoute

data class ManageDataUiState(
    val dataList: List<CompleteLocation> = emptyList(),
    val collectionRoutes: List<CompleteCollectionRoute> = emptyList(),
    val isLoading: Boolean = false
)

package com.milega.app.ui.customer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.milega.app.AppContainer
import com.milega.app.R
import com.milega.app.data.ExternalPlace
import com.milega.app.data.ShopDto
import com.milega.app.ui.components.EmptyState
import com.milega.app.ui.components.GoogleAttribution
import com.milega.app.ui.components.LoadableContent
import com.milega.app.ui.components.rememberLoadable
import com.milega.app.ui.theme.Space
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class SavedData(val shops: List<ShopDto>, val places: List<ExternalPlace>)

@Composable
fun SavedScreen(container: AppContainer, onShop: (Long) -> Unit, onPlace: (String) -> Unit, onLogin: () -> Unit) {
    val session = container.session.current
    if (!session.loggedIn) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            EmptyState(Icons.Filled.FavoriteBorder, stringResource(R.string.saved_login_title), stringResource(R.string.saved_login_body),
                stringResource(R.string.log_in), onLogin)
        }
        return
    }
    val state = rememberLoadable(Unit) {
        val saved = container.api.saved()
        // Google place details are fetched live (only the place ID is stored), basic fields only.
        val places = coroutineScope {
            saved.places.take(15).map { ref ->
                async { runCatching { container.api.place(ref.placeId, session.lat, session.lng, basic = true).place }.getOrNull() }
            }.awaitAll().filterNotNull()
        }
        SavedData(saved.shops, places)
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text(stringResource(R.string.nav_saved), style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = Space.gutter, top = Space.lg, end = Space.gutter))
        LoadableContent(state) { data ->
            if (data.shops.isEmpty() && data.places.isEmpty()) {
                EmptyState(Icons.Filled.FavoriteBorder, stringResource(R.string.saved_empty_title), stringResource(R.string.saved_empty_body))
            } else {
                LazyColumn(contentPadding = PaddingValues(Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    if (data.shops.isNotEmpty()) {
                        item { Text(stringResource(R.string.milega_merchants), style = MaterialTheme.typography.titleMedium) }
                        items(data.shops, key = { "m" + it.id }) { s -> MerchantRow(s) { onShop(s.id) } }
                    }
                    if (data.places.isNotEmpty()) {
                        item { Text(stringResource(R.string.saved_from_google), style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = Space.md)) }
                        items(data.places, key = { "g" + it.placeId }) { p -> ExternalPlaceRow(p) { onPlace(p.placeId) } }
                        item { GoogleAttribution() }
                    }
                }
            }
        }
    }
}

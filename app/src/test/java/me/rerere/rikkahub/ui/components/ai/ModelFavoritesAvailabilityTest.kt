package me.rerere.rikkahub.ui.components.ai

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.*
import org.junit.Test

class ModelFavoritesAvailabilityTest {
    @Test fun `disabling provider hides its favorite until reenabled without erasing saved preference`() {
        val model = Model(modelId = "model", displayName = "Model")
        val provider = ProviderSetting.OpenAI(name = "Provider", models = listOf(model))
        val favorites = listOf(model.id)
        val state = ModelListState(model.id, listOf(provider), ModelType.CHAT)
        assertEquals(listOf(model), pickerFavoriteModels(favorites, state.filteredProviders, state.type).map { it.first })
        state.update(model.id, listOf(provider.copy(enabled = false)), ModelType.CHAT)
        assertTrue(state.filteredProviders.isEmpty())
        assertTrue(pickerFavoriteModels(favorites, state.providers, state.type).isEmpty())
        state.update(model.id, listOf(provider), ModelType.CHAT)
        assertEquals(model, pickerFavoriteModels(favorites, state.filteredProviders, state.type).single().first)
        assertEquals(listOf(model.id), favorites)
    }

    @Test fun `favorite must also be in requested picker model type`() {
        val model = Model(modelId = "embedding", displayName = "Embedding", type = ModelType.EMBEDDING)
        val provider = ProviderSetting.OpenAI(name = "Provider", models = listOf(model))
        assertTrue(pickerFavoriteModels(listOf(model.id), listOf(provider), ModelType.CHAT).isEmpty())
    }
}

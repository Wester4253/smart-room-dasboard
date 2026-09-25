package com.example.smartroomdashboard.data.remote

import com.example.smartroomdashboard.domain.Todo

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

data class HomeAssistantState(
    val entity_id: String = "",
    val state: String = "",
    val attributes: Map<String, Any?> = emptyMap(),
)

data class RemoteTodo(
    val summary: String = "",
    val uid: String? = null,
    val status: String? = null,
    val due: String? = null,
    val description: String? = null,
)

interface HomeAssistantApi {
    @GET("api/states")
    suspend fun getStates(): Response<List<HomeAssistantState>>
}

fun RemoteTodo.toDomain(listEntityId: String = "") = Todo(
    id = uid ?: summary,
    title = summary,
    description = description.orEmpty(),
    completed = status.equals("completed", ignoreCase = true),
    dueDate = due,
    listEntityId = listEntityId,
)

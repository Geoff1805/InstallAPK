package fr.installapk.app.data

data class TokenValidationResult(
    val isValid: Boolean,
    val statusCode: Int = 200,
    val username: String? = null,
    val expirationDate: String? = null,
    val scopes: String? = null,
    val errorMessage: String? = null
)

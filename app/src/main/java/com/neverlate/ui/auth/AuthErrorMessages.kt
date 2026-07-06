package com.neverlate.ui.auth

import com.neverlate.R
import com.neverlate.data.auth.AuthErrorType

/**
 * Maps an [AuthErrorType] to the string resource its screen should show — kept as a plain
 * function (not `@Composable`) since a string resource id is just an `Int`, same reasoning as
 * [com.neverlate.data.tasks.TaskValidationError] being mapped to resources in `TaskEditScreen`.
 */
fun authErrorMessageRes(type: AuthErrorType): Int = when (type) {
    AuthErrorType.EMAIL_TAKEN -> R.string.auth_error_email_taken
    AuthErrorType.INVALID_CREDENTIALS -> R.string.auth_error_invalid_credentials
    AuthErrorType.VALIDATION -> R.string.auth_error_validation
    AuthErrorType.NETWORK -> R.string.auth_error_network
    AuthErrorType.UNKNOWN -> R.string.auth_error_unknown
}

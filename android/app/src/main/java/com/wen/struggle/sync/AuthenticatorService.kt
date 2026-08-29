package com.wen.struggle.sync

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.NetworkErrorException
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

class AccountAuthenticator : AbstractAccountAuthenticator(null) {
    override fun addAccount(p0: AccountAuthenticatorResponse?, p1: String?, p2: String?, p3: Array<out String>?, p4: Bundle?): Bundle? = null
    override fun getAuthToken(p0: AccountAuthenticatorResponse?, p1: Account?, p2: String?, p3: Bundle?): Bundle? = null
    override fun getAuthTokenLabel(p0: String?): String? = null
    override fun confirmCredentials(p0: AccountAuthenticatorResponse?, p1: Account?, p2: Bundle?): Bundle? = null
    override fun updateCredentials(p0: AccountAuthenticatorResponse?, p1: Account?, p2: String?, p3: Bundle?): Bundle? = null
    override fun hasFeatures(p0: AccountAuthenticatorResponse?, p1: Account?, p2: Array<out String>?): Bundle? = null
    override fun editProperties(p0: AccountAuthenticatorResponse?, p1: String?): Bundle? = null
}

class AuthenticatorService : Service() {
    private var authenticator: AccountAuthenticator? = null
    override fun onCreate() {
        super.onCreate()
        authenticator = AccountAuthenticator()
    }
    override fun onBind(intent: Intent?): IBinder? = authenticator?.iBinder
}

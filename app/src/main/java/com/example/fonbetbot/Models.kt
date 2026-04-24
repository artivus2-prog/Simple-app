// Models.kt
package com.example.fonbetbot

data class AuthData(val fsid: String, val deviceId: String)
data class ExpressWithMatches(val express: ExpressInfo, val matches: List<MatchInfo>)
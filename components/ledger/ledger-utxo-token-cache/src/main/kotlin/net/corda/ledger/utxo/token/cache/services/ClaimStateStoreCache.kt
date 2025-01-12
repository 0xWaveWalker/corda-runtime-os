package net.corda.ledger.utxo.token.cache.services

import net.corda.ledger.utxo.token.cache.entities.TokenPoolKey

interface ClaimStateStoreCache {
    fun get(key: TokenPoolKey): ClaimStateStore
}
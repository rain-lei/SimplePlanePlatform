package com.example.conflict;

import io.loglens.api.ChainBuildContext;
import io.loglens.api.ChainDefinition;
import io.loglens.api.LogChainProvider;

/** Installed beside another whole-chain provider only to verify startup conflict handling. */
public final class ConflictingChainProvider implements LogChainProvider {
    @Override public ChainDefinition create(ChainBuildContext context) {
        return new ChainDefinition(context.configuredNodes());
    }
}

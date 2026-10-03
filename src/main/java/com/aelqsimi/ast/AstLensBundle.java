package com.aelqsimi.ast;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;

public final class AstLensBundle {
    @NonNls
    private static final String BUNDLE = "messages.AstLensBundle";
    private static final DynamicBundle INSTANCE = new DynamicBundle(AstLensBundle.class, BUNDLE);

    private AstLensBundle() {
    }

    public static @NotNull @Nls String message(
            @NotNull @PropertyKey(resourceBundle = BUNDLE) String key,
            Object @NotNull ... params
    ) {
        return INSTANCE.getMessage(key, params);
    }
}

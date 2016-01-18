package pl.codewise.amazon.client.utils;

import io.netty.util.concurrent.FastThreadLocal;
import javolution.text.TextBuilder;

public class TextBuilders {

    private static final FastThreadLocal<TextBuilder> THREAD_LOCAL = new FastThreadLocal<TextBuilder>() {

        @Override
        protected TextBuilder initialValue() {
            return new TextBuilder();
        }
    };

    public static TextBuilder threadLocal() {
        TextBuilder result = THREAD_LOCAL.get();
        result.clear();

        return result;
    }
}

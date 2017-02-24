package com.github.axet.audiolibrary.encoders;

public interface Encoder {
    void encode(short[] buf, int len);

    void close();
}

package com.proxy.common.crypto;

/**
 * 加密配置
 */
public class CipherConfig {

    private byte[] key;
    private byte[] iv;
    private String algorithm;
    private boolean serverSide;

    /** Endpoint role for direction-separated suites; legacy suites ignore it. */
    public boolean isServerSide() {
        return serverSide;
    }

    public void setServerSide(boolean serverSide) {
        this.serverSide = serverSide;
    }

    public CipherConfig() {
    }

    public CipherConfig(byte[] key) {
        this.key = key;
    }

    public CipherConfig(byte[] key, String algorithm) {
        this.key = key;
        this.algorithm = algorithm;
    }

    public byte[] getKey() {
        return key;
    }

    public void setKey(byte[] key) {
        this.key = key;
    }

    public byte[] getIv() {
        return iv;
    }

    public void setIv(byte[] iv) {
        this.iv = iv;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }
}

package me.crema.novelia.account;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.KeyPairGeneratorSpec;
import android.util.Base64;
import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.util.Arrays;
import java.util.Calendar;
import javax.crypto.Cipher;
import javax.security.auth.x500.X500Principal;

/** API18+ RSA keystore wrapping BOTH AES and MAC keys. No plaintext fallback. */
public final class CredentialStore {
    public static final int MAX_EMAIL_UTF8_BYTES = 512, MAX_PASSWORD_UTF8_BYTES = 4096;
    public static final int MAX_BLOB_BASE64_CHARS = 8000;
    public static final String KEY_BLOB = "blob";
    private final Context context;
    private final SharedPreferences prefs;
    private final String alias;

    public CredentialStore(Context context) { this(context, "default"); }
    /** Isolated namespace permits tests without reading or overwriting real credentials. */
    public CredentialStore(Context context, String namespace) {
        if (context == null || namespace == null || !namespace.matches("[a-z0-9_]{1,40}"))
            throw new IllegalArgumentException("invalid credential namespace");
        this.context = context.getApplicationContext();
        String suffix = "default".equals(namespace) ? "" : "_" + namespace;
        prefs = this.context.getSharedPreferences("novelia_account_v1" + suffix, Context.MODE_PRIVATE);
        alias = "novelia_account_key" + suffix;
    }
    public boolean hasSaved() { return prefs.contains(KEY_BLOB); }
    public boolean isAutoLoginEnabled() {
        try { return hasSaved() && prefs.getBoolean("autoLogin", false); }
        catch (RuntimeException bad) { return false; }
    }
    public void setAutoLoginEnabled(boolean enabled) throws IOException {
        if (enabled && !hasSaved()) throw new IOException("no saved credentials");
        if (!prefs.edit().putBoolean("autoLogin", enabled).commit()) throw new IOException("credential write failed");
    }
    public synchronized void save(String email, String password, boolean autoLogin)
            throws IOException, GeneralSecurityException {
        byte[] e = CryptoEnvelope.encodeUtf8(email == null ? "" : email.trim());
        byte[] p = CryptoEnvelope.encodeUtf8(password == null ? "" : password);
        byte[] keys = new byte[64], payload = null;
        try {
            if (e.length == 0 || e.length > MAX_EMAIL_UTF8_BYTES || p.length == 0 || p.length > MAX_PASSWORD_UTF8_BYTES)
                throw new IOException("invalid credential length");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(bytes);
            data.writeInt(e.length); data.write(e); data.writeInt(p.length); data.write(p);
            payload = bytes.toByteArray();
            new SecureRandom().nextBytes(keys);
            KeyStore store = keystore(); ensureKey(store);
            Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(Cipher.ENCRYPT_MODE, store.getCertificate(alias).getPublicKey());
            byte[] wrapped = rsa.doFinal(keys);
            byte[] encrypted = encrypt(keys, payload);
            bytes = new ByteArrayOutputStream(); data = new DataOutputStream(bytes);
            data.writeByte(1); data.writeShort(wrapped.length); data.write(wrapped); data.write(encrypted);
            String encoded = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
            if (encoded.length() > MAX_BLOB_BASE64_CHARS) throw new IOException("credential blob too large");
            if (!prefs.edit().putString(KEY_BLOB, encoded).putBoolean("autoLogin", autoLogin).commit())
                throw new IOException("credential write failed");
        } catch (RuntimeException error) { throw new GeneralSecurityException("credential storage unavailable"); }
        finally { Arrays.fill(e,(byte)0); Arrays.fill(p,(byte)0); Arrays.fill(keys,(byte)0); if(payload!=null)Arrays.fill(payload,(byte)0); }
    }
    public synchronized Credentials load() throws IOException, GeneralSecurityException {
        byte[] keys = null, payload = null;
        try {
            String encoded = prefs.getString(KEY_BLOB, "");
            if (encoded.isEmpty() || encoded.length() > MAX_BLOB_BASE64_CHARS) throw new IOException("credential blob unavailable");
            byte[] blob = Base64.decode(encoded, Base64.NO_WRAP);
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(blob));
            if(input.readUnsignedByte()!=1 || input.readUnsignedShort()!=256 || blob.length<=259)
                throw new GeneralSecurityException("corrupt credential blob");
            byte[] wrapped = new byte[256]; input.readFully(wrapped);
            byte[] envelope = new byte[input.available()]; input.readFully(envelope);
            KeyStore store = keystore();
            Key key = store.getKey(alias, null);
            if (!(key instanceof PrivateKey)) throw new GeneralSecurityException("credential key unavailable");
            Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(Cipher.DECRYPT_MODE, key); keys = rsa.doFinal(wrapped);
            if(keys.length!=64) throw new GeneralSecurityException("corrupt credential blob");
            payload = decrypt(keys, envelope);
            input = new DataInputStream(new ByteArrayInputStream(payload));
            String email = readText(input, MAX_EMAIL_UTF8_BYTES);
            String password = readText(input, MAX_PASSWORD_UTF8_BYTES);
            if(input.available()!=0) throw new GeneralSecurityException("corrupt credential blob");
            return new Credentials(email,password);
        } catch (RuntimeException error) { throw new GeneralSecurityException("credential storage unavailable"); }
        finally { if(keys!=null)Arrays.fill(keys,(byte)0); if(payload!=null)Arrays.fill(payload,(byte)0); }
    }
    public synchronized void clear() throws IOException {
        // Never delete the key while an encrypted blob still exists on disk.
        if(!prefs.edit().clear().commit()) throw new IOException("credential deletion failed");
        try { KeyStore store = keystore(); if(store.containsAlias(alias))store.deleteEntry(alias); }
        catch (Exception ignored) { /* Blob and auto-login already removed. */ }
    }
    private static byte[] encrypt(byte[] keys,byte[] payload) throws IOException,GeneralSecurityException {
        byte[] aes=Arrays.copyOfRange(keys,0,32), mac=Arrays.copyOfRange(keys,32,64);
        try{return CryptoEnvelope.encrypt(aes,mac,payload);}finally{Arrays.fill(aes,(byte)0);Arrays.fill(mac,(byte)0);}
    }
    private static byte[] decrypt(byte[] keys,byte[] payload) throws IOException,GeneralSecurityException {
        byte[] aes=Arrays.copyOfRange(keys,0,32), mac=Arrays.copyOfRange(keys,32,64);
        try{return CryptoEnvelope.decrypt(aes,mac,payload);}finally{Arrays.fill(aes,(byte)0);Arrays.fill(mac,(byte)0);}
    }
    private static String readText(DataInputStream input,int max) throws IOException {
        int length=input.readInt();
        if(length<1||length>max||length>input.available())throw new IOException("corrupt credential blob");
        byte[] bytes=new byte[length]; input.readFully(bytes);
        try{return new String(bytes,"UTF-8");}finally{Arrays.fill(bytes,(byte)0);}
    }
    private static KeyStore keystore() throws GeneralSecurityException,IOException {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);return store;
    }
    private void ensureKey(KeyStore store) throws GeneralSecurityException,IOException {
        if(store.containsAlias(alias)) return;
        Calendar start=Calendar.getInstance(),end=Calendar.getInstance();
        start.set(2000,0,1);end.set(2100,0,1);
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA","AndroidKeyStore");
        generator.initialize(new KeyPairGeneratorSpec.Builder(context).setAlias(alias).setKeySize(2048)
                .setSubject(new X500Principal("CN=Novelpia eink credentials"))
                .setSerialNumber(BigInteger.ONE).setStartDate(start.getTime()).setEndDate(end.getTime()).build());
        generator.generateKeyPair();
    }
    public static final class Credentials {
        public final String email,password;
        public Credentials(String email,String password){this.email=email;this.password=password;}
    }
}

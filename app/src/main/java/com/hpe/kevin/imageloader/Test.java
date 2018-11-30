package com.hpe.kevin.imageloader;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class Test {
    public static void main(String[] args) {
//        System.out.println("http://www.baidu.com".getBytes());
        try {
            final MessageDigest digest = MessageDigest.getInstance("MD5");
            digest.update("http://www.baidu.com".getBytes());
            byte[] bytes = digest.digest();
            for (byte b : bytes) {
                System.out.println(b);
                System.out.println(Integer.toHexString(0xFF & b));
//                System.out.println(Integer.toHexString(b));
            }
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
    }
}

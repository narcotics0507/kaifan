package com.scaffold;

import cn.hutool.crypto.digest.BCrypt;

/**
 * 密码测试工具
 */
public class PasswordTest {

    public static void main(String[] args) {
        String password = "123456";

        // 生成新的密码哈希
        String hash = BCrypt.hashpw(password, BCrypt.gensalt());
        System.out.println("新密码哈希: " + hash);

        // 测试旧哈希
        String oldHash = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZRGdjGj/n3.rsQ0fBZVEHZxOusS3K";
        boolean match = BCrypt.checkpw(password, oldHash);
        System.out.println("旧哈希验证结果: " + match);

        // 测试新哈希
        boolean newMatch = BCrypt.checkpw(password, hash);
        System.out.println("新哈希验证结果: " + newMatch);
    }
}

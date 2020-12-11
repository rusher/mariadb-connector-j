/*
 * MariaDB Client for Java
 *
 * Copyright (c) 2012-2014 Monty Program Ab.
 * Copyright (c) 2015-2020 MariaDB Corporation Ab.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along
 * with this library; if not, write to Monty Program Ab info@montyprogram.com.
 *
 */

package org.mariadb.jdbc.plugin.authentication.standard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.Arrays;
import org.mariadb.jdbc.Configuration;
import org.mariadb.jdbc.client.ReadableByteBuf;
import org.mariadb.jdbc.client.context.Context;
import org.mariadb.jdbc.client.socket.PacketReader;
import org.mariadb.jdbc.client.socket.PacketWriter;
import org.mariadb.jdbc.plugin.authentication.AuthenticationPlugin;

public class NativePasswordPlugin implements AuthenticationPlugin {

  public static final String TYPE = "mysql_native_password";

  private String authenticationData;
  private byte[] seed;

  /**
   * Encrypts a password.
   *
   * <p>protocol for authentication is like this: 1. Server sends a random array of bytes (the seed)
   * 2. client makes a sha1 digest of the password 3. client hashes the output of 2 4. client
   * digests the seed 5. client updates the digest with the output from 3 6. an xor of the output of
   * 5 and 2 is sent to server 7. server does the same thing and verifies that the scrambled
   * passwords match
   *
   * @param password the password to encrypt
   * @param seed the seed to use
   * @return a scrambled password
   * @throws NoSuchAlgorithmException if SHA1 is not available on the platform we are using
   */
  public static byte[] encryptPassword(final String password, final byte[] seed)
      throws NoSuchAlgorithmException {

    if (password == null || password.isEmpty()) {
      return new byte[0];
    }

    final MessageDigest messageDigest = MessageDigest.getInstance("SHA-1");
    byte[] bytePwd = password.getBytes(StandardCharsets.UTF_8);

    final byte[] stage1 = messageDigest.digest(bytePwd);
    messageDigest.reset();

    final byte[] stage2 = messageDigest.digest(stage1);
    messageDigest.reset();

    messageDigest.update(seed);
    messageDigest.update(stage2);

    final byte[] digest = messageDigest.digest();
    final byte[] returnBytes = new byte[digest.length];
    for (int i = 0; i < digest.length; i++) {
      returnBytes[i] = (byte) (stage1[i] ^ digest[i]);
    }
    return returnBytes;
  }

  @Override
  public String name() {
    return "mysql native password";
  }

  @Override
  public String type() {
    return TYPE;
  }

  /**
   * Initialized data.
   *
   * @param authenticationData authentication data (password/token)
   * @param seed server provided seed
   * @param conf Connection string options
   */
  public void initialize(String authenticationData, byte[] seed, Configuration conf) {
    this.seed = seed;
    this.authenticationData = authenticationData;
  }

  /**
   * Process native password plugin authentication. see
   * https://mariadb.com/kb/en/library/authentication-plugin-mysql_native_password/
   *
   * @param out out stream
   * @param in in stream
   * @param context connection context
   * @return response packet
   * @throws IOException if socket error
   */
  public ReadableByteBuf process(PacketWriter out, PacketReader in, Context context)
      throws SQLException, IOException {
    if (authenticationData == null || authenticationData.isEmpty()) {
      out.writeEmptyPacket();
    } else {
      try {

        byte[] truncatedSeed;
        if (seed.length > 0) {
          // Seed is ended with a null byte value.
          truncatedSeed = Arrays.copyOfRange(seed, 0, seed.length - 1);
        } else {
          truncatedSeed = new byte[0];
        }
        out.writeBytes(encryptPassword(authenticationData, truncatedSeed));
        out.flush();
      } catch (NoSuchAlgorithmException e) {
        throw new RuntimeException("Could not use SHA-1, failing", e);
      }
    }

    return in.readPacket(true);
  }
}

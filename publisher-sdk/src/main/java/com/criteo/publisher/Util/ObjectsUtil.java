package com.criteo.publisher.Util;

import java.util.Arrays;
import java.util.List;

/**
 * Utility methods copied from the Standard Objects Java class. This class is only available starting
 * from Java 8, which is only available since API level 19. However we support devices
 * down to API level 16.
 *
 * <p>
 *    This utility class can therefore be removed once the minTargetSdk is set to 19 again.
 * </p>
 *
 */
public class ObjectsUtil {

  /**
   * Returns {@code true} if the arguments are equal to each other
   * and {@code false} otherwise.
   * Consequently, if both arguments are {@code null}, {@code true}
   * is returned and if exactly one argument is {@code null}, {@code
   * false} is returned.  Otherwise, equality is determined by using
   * the {@link Object#equals equals} method of the first
   * argument.
   *
   * @param a an object
   * @param b an object to be compared with {@code a} for equality
   * @return {@code true} if the arguments are equal to each other
   * and {@code false} otherwise
   * @see Object#equals(Object)
   */
  public static boolean equals(Object a, Object b) {
    return (a == b) || (a != null && a.equals(b));
  }


  /**
   * Generates a hash code for a sequence of input values. The hash
   * code is generated as if all the input values were placed into an
   * array, and that array were hashed by calling {@link
   * Arrays#hashCode(Object[])}.
   *
   * <p>This method is useful for implementing {@link
   * Object#hashCode()} on objects containing multiple fields. For
   * example, if an object that has three fields, {@code x}, {@code
   * y}, and {@code z}, one could write:
   *
   * <blockquote><pre>
   * &#064;Override public int hashCode() {
   *     return Objects.hash(x, y, z);
   * }
   * </pre></blockquote>
   *
   * @param values the values to be hashed
   * @return a hash value of the sequence of input values
   * @see Arrays#hashCode(Object[])
   * @see List#hashCode
   */
  public static int hash(Object... values) {
    return Arrays.hashCode(values);
  }
}

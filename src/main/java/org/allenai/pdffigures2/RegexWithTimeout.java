package org.allenai.pdffigures2;


import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RegexWithTimeout {
  public static class RegexTimeout extends RuntimeException { }

  public static Matcher matcher(final Pattern pattern, final CharSequence string) {
    final long timeout = 1500; //ms

    class TimeoutCharSequence implements CharSequence {
      private CharSequence inner;
      private long abortTime;

      public TimeoutCharSequence(final CharSequence inner, final long abortTime) {
        super();
        this.inner = inner;
        this.abortTime = abortTime;
      }

      public char charAt(int index) {
        if(System.currentTimeMillis() >= abortTime)
          throw new RegexTimeout();

        return inner.charAt(index);
      }

      public int length() {
        return inner.length();
      }

      public CharSequence subSequence(int start, int end) {
        return new TimeoutCharSequence(inner.subSequence(start, end), abortTime);
      }

      public String toString() {
        return inner.toString();
      }
    }

    return pattern.matcher(new TimeoutCharSequence(string, System.currentTimeMillis() + timeout));
  }
}

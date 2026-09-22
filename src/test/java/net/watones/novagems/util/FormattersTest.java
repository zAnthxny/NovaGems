package net.watones.novagems.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FormattersTest {
  @Test
  void groupsThousandsLikeTheUsLocale() {
    assertThat(Formatters.number(0)).isEqualTo("0");
    assertThat(Formatters.number(999)).isEqualTo("999");
    assertThat(Formatters.number(1000)).isEqualTo("1,000");
    assertThat(Formatters.number(10_000)).isEqualTo("10,000");
    assertThat(Formatters.number(100_000)).isEqualTo("100,000");
    assertThat(Formatters.number(1_234_567)).isEqualTo("1,234,567");
    assertThat(Formatters.number(10_000_000)).isEqualTo("10,000,000");
    assertThat(Formatters.number(-1_500)).isEqualTo("-1,500");
    assertThat(Formatters.number(Long.MAX_VALUE)).isEqualTo("9,223,372,036,854,775,807");
    assertThat(Formatters.number(Long.MIN_VALUE)).isEqualTo("-9,223,372,036,854,775,808");
  }

  @Test
  void compactKeepsExactDigitsBelowOneThousand() {
    assertThat(Formatters.compact(0)).isEqualTo("0");
    assertThat(Formatters.compact(999)).isEqualTo("999");
  }

  @Test
  void compactScalesAndStripsTrailingZeroFraction() {
    assertThat(Formatters.compact(1_000)).isEqualTo("1k");
    assertThat(Formatters.compact(1_049)).isEqualTo("1k");
    assertThat(Formatters.compact(1_500)).isEqualTo("1.5k");
    assertThat(Formatters.compact(10_500)).isEqualTo("10.5k");
    assertThat(Formatters.compact(1_000_000)).isEqualTo("1m");
    assertThat(Formatters.compact(1_500_000)).isEqualTo("1.5m");
    assertThat(Formatters.compact(10_000_000)).isEqualTo("10m");
    assertThat(Formatters.compact(1_000_000_000L)).isEqualTo("1b");
    assertThat(Formatters.compact(1_000_000_000_000L)).isEqualTo("1t");
  }

  @Test
  void compactCarriesIntoTheNextUnitWhenRoundingOverflows() {
    assertThat(Formatters.compact(1_999)).isEqualTo("2k");
    assertThat(Formatters.compact(9_999)).isEqualTo("10k");
    assertThat(Formatters.compact(999_999)).isEqualTo("1m");
    assertThat(Formatters.compact(999_999_999L)).isEqualTo("1b");
  }

  @Test
  void compactStaysInTrillionsBeyondTheLastUnit() {
    assertThat(Formatters.compact(9_007_199_254_740_993L)).isEqualTo("9007.2t");
    assertThat(Formatters.compact(Long.MAX_VALUE)).isEqualTo("9223372t");
  }

  @Test
  void compactHandlesNegativesWithoutOverflowing() {
    assertThat(Formatters.compact(-1)).isEqualTo("-1");
    assertThat(Formatters.compact(-1_500)).isEqualTo("-1.5k");
    assertThat(Formatters.compact(-10_000_000)).isEqualTo("-10m");
    assertThat(Formatters.compact(Long.MIN_VALUE)).isEqualTo("-9223372t");
  }
}

package com.ledgerly.receipts.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FileNamesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
                    lunch receipt.pdf          | application/pdf | lunch_receipt.pdf
                    ../../etc/passwd           | application/pdf | passwd.pdf
                    ..\\..\\windows\\evil.pdf  | application/pdf | evil.pdf
                    .hidden.png                | image/png       | hidden.png
                    scan.exe                   | application/pdf | scan.pdf
                    photo.PDF                  | image/jpeg      | photo.jpg
                    na"me;rm -rf.pdf           | application/pdf | na_me_rm_-rf.pdf
                    ___.pdf                    | application/pdf | receipt.pdf
                    """)
    void keepsOnlySafeCharactersAndTheRealExtension(String original, String type, String expected) {
        assertThat(FileNames.sanitize(original.strip(), type.strip())).isEqualTo(expected.strip());
    }

    @Test
    void neverContainsPathSeparatorsQuotesOrControlCharacters() {
        String name = FileNames.sanitize("a/b\\c\"d\r\ne\u0000f.pdf", "application/pdf");
        assertThat(name).matches("[A-Za-z0-9_-]+\\.pdf");
    }

    @Test
    void longNamesAreShortened() {
        assertThat(FileNames.sanitize("x".repeat(500) + ".pdf", "application/pdf")
                        .length())
                .isLessThanOrEqualTo(84);
    }
}

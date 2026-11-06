package com.ledgerly.expenses.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CsvCellsTest {

    @Test
    void cellsThatCouldBeFormulasGetALeadingQuote() {
        assertThat(CsvCells.cell("=SUM(A1:A9)")).isEqualTo("'=SUM(A1:A9)");
        assertThat(CsvCells.cell("+1")).isEqualTo("'+1");
        assertThat(CsvCells.cell("-2")).isEqualTo("'-2");
        assertThat(CsvCells.cell("@cmd")).isEqualTo("'@cmd");
    }

    @Test
    void ordinaryCellsAreLeftAlone() {
        assertThat(CsvCells.cell("plain")).isEqualTo("plain");
        assertThat(CsvCells.cell("Cafe =Central")).isEqualTo("Cafe =Central");
        assertThat(CsvCells.cell("12.50")).isEqualTo("12.50");
    }

    @Test
    void tabAndCarriageReturnAtTheStartAreNeutralisedToo() {
        assertThat(CsvCells.neutralise("\tcmd")).isEqualTo("'\tcmd");
        assertThat(CsvCells.neutralise("\rcmd")).isEqualTo("'\rcmd");
    }

    @Test
    void emptyStaysEmpty() {
        assertThat(CsvCells.cell("")).isEmpty();
    }

    @Test
    void commasQuotesAndNewlinesAreQuotedPerRfc4180() {
        assertThat(CsvCells.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(CsvCells.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(CsvCells.cell("two\nlines")).isEqualTo("\"two\nlines\"");
    }

    @Test
    void aNeutralisedCellThatAlsoNeedsQuotingGetsBoth() {
        assertThat(CsvCells.cell("=A1,B1")).isEqualTo("\"'=A1,B1\"");
    }

    @Test
    void rowsEndWithCrlf() {
        assertThat(CsvCells.row("a", "b,c", "=x")).isEqualTo("a,\"b,c\",'=x\r\n");
    }
}

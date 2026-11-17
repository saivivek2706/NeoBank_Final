package com.neobank.account.service;

import com.neobank.account.domain.MovementDirection;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatementCsvParserTest {

    @Test
    void parsesAValidStatement() {
        String csv = """
                reference,direction,amount,currency,description
                BANK-001,INFLOW,500.00,sgd,Incoming FAST payment
                BANK-002,outflow,40.5,SGD,
                """;

        List<StatementReconciliationService.LineInput> lines = StatementCsvParser.parse(csv);

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).reference()).isEqualTo("BANK-001");
        assertThat(lines.get(0).direction()).isEqualTo(MovementDirection.INFLOW);
        assertThat(lines.get(0).amount()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(lines.get(0).currency()).isEqualTo("SGD");           // normalised to upper case
        assertThat(lines.get(0).description()).isEqualTo("Incoming FAST payment");
        assertThat(lines.get(1).direction()).isEqualTo(MovementDirection.OUTFLOW);
    }

    @Test
    void quotedFieldsMayContainCommasAndQuotes() {
        String csv = "reference,direction,amount,currency,description\nR1,INFLOW,1.00,SGD,\"Acme, Inc. \"\"Q3\"\"\"\n";

        assertThat(StatementCsvParser.parse(csv).get(0).description()).isEqualTo("Acme, Inc. \"Q3\"");
    }

    @Test
    void skipsBlankLinesAndHandlesWindowsLineEndings() {
        String csv = "reference,direction,amount,currency\r\nR1,INFLOW,1.00,SGD\r\n\r\nR2,OUTFLOW,2.00,SGD\r\n";

        assertThat(StatementCsvParser.parse(csv)).hasSize(2);
    }

    @Test
    void rejectsABadHeader() {
        assertThatThrownBy(() -> StatementCsvParser.parse("ref,dir,amt,ccy\nR1,INFLOW,1,SGD"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Line 1");
    }

    @Test
    void errorsNameTheOffendingLine() {
        String header = "reference,direction,amount,currency\n";

        assertThatThrownBy(() -> StatementCsvParser.parse(header + "R1,SIDEWAYS,1,SGD"))
                .hasMessageContaining("Line 2").hasMessageContaining("INFLOW or OUTFLOW");
        assertThatThrownBy(() -> StatementCsvParser.parse(header + "R1,INFLOW,1,SGD\nR2,INFLOW,abc,SGD"))
                .hasMessageContaining("Line 3").hasMessageContaining("not a number");
        assertThatThrownBy(() -> StatementCsvParser.parse(header + "R1,INFLOW,-5,SGD"))
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> StatementCsvParser.parse(header + ",INFLOW,5,SGD"))
                .hasMessageContaining("reference is empty");
        assertThatThrownBy(() -> StatementCsvParser.parse(header + "R1,INFLOW"))
                .hasMessageContaining("at least 4 columns");
    }

    @Test
    void rejectsAnEmptyFile() {
        assertThatThrownBy(() -> StatementCsvParser.parse("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StatementCsvParser.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }
}

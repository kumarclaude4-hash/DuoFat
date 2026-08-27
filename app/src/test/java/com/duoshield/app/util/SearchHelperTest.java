package com.duoshield.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SearchHelperTest {
    @Test
    public void buildsConservativeAndPrefixExpression() {
        assertEquals("\"hello\"* AND \"world\"*",
                SearchHelper.toFtsMatchExpression("hello world"));
    }

    @Test
    public void quotesFtsOperatorsAndEmbeddedQuotes() {
        String expression = SearchHelper.toFtsMatchExpression("one OR two \"three\"");
        assertTrue(expression.contains("\"one\"*"));
        assertTrue(expression.contains("\"or\"*"));
        assertTrue(expression.contains("\"two\"*"));
        assertTrue(expression.contains("\"three\"\"\"*"));
        assertTrue(expression.contains(" AND "));
    }

    @Test
    public void emptyInputProducesAHarmlessNoMatchExpression() {
        assertEquals("\"\"", SearchHelper.toFtsMatchExpression("   "));
    }
}

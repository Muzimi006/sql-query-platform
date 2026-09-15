package com.example.sqlquery.util;

import com.example.sqlquery.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlValidateUtilTest {

    @Test
    void shouldAllowSelect() {
        assertDoesNotThrow(() -> SqlValidateUtil.validateSelect("select * from user"));
    }

    @Test
    void shouldRejectDelete() {
        assertThrows(BusinessException.class,
                () -> SqlValidateUtil.validateSelect("delete from user"));
    }

    @Test
    void shouldRejectDrop() {
        assertThrows(BusinessException.class,
                () -> SqlValidateUtil.validateSelect("drop table user"));
    }
}

/*
 *  Copyright The Athenz Authors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.yahoo.athenz.common.server.cert.impl;

import com.yahoo.athenz.common.server.cert.CertRecordStoreConnection;
import com.yahoo.athenz.common.server.status.StatusCheckException;
import com.yahoo.athenz.common.server.ServerResourceException;
import org.mockito.Mockito;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

public class JDBCCertRecordStoreStatusCheckerTest {

    @Test
    public void testCheck() throws Exception {
        JDBCCertRecordStore jdbcCertRecordStore = Mockito.mock(JDBCCertRecordStore.class);
        CertRecordStoreConnection certRecordStoreConnection = Mockito.mock(CertRecordStoreConnection.class);
        Mockito.when(jdbcCertRecordStore.getConnection()).thenReturn(certRecordStoreConnection);

        JDBCCertRecordStoreStatusChecker jdbcCertRecordStoreStatusChecker =
                new JDBCCertRecordStoreStatusChecker(jdbcCertRecordStore);
        jdbcCertRecordStoreStatusChecker.check();

        Mockito.verify(jdbcCertRecordStore, Mockito.times(1)).getConnection();
        Mockito.verify(certRecordStoreConnection, Mockito.times(1)).close();
    }

    @Test
    public void testCheckNoDBConnection() throws Exception {
        JDBCCertRecordStore jdbcCertRecordStore = Mockito.mock(JDBCCertRecordStore.class);
        Mockito.when(jdbcCertRecordStore.getConnection()).thenThrow(new ServerResourceException(503));

        JDBCCertRecordStoreStatusChecker jdbcCertRecordStoreStatusChecker =
                new JDBCCertRecordStoreStatusChecker(jdbcCertRecordStore);
        try {
            jdbcCertRecordStoreStatusChecker.check();
            fail();
        } catch (StatusCheckException ex) {
            assertEquals(ex.getCode(), 500);
            assertEquals(ex.getMsg(), "SERVICE_UNAVAILABLE");
        }

        Mockito.verify(jdbcCertRecordStore, Mockito.times(1)).getConnection();
    }
}

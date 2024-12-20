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

package io.athenz.server.aws.common.utils;

import org.testng.annotations.Test;

import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.*;

public class DynamoDBUtilsTest {

    @Test
    public void testGetIso8601FromDate() {
        long epoch = 1591706189000L;
        Date now = new Date(epoch);
        Date anHourAgo = new Date(epoch - TimeUnit.HOURS.toMillis(1));
        Date yesterday = new Date(epoch - TimeUnit.DAYS.toMillis(1));
        Date lastMonth = new Date(epoch - TimeUnit.DAYS.toMillis(30));

        assertEquals(DynamoDBUtils.getIso8601FromDate(now), "2020-06-09");
        assertEquals(DynamoDBUtils.getIso8601FromDate(anHourAgo), "2020-06-09");
        assertEquals(DynamoDBUtils.getIso8601FromDate(yesterday), "2020-06-08");
        assertEquals(DynamoDBUtils.getIso8601FromDate(lastMonth), "2020-05-10");
    }

    @Test
    public void testGetUnrefreshedCertDates() {
        long epoch = 1591706189000L;
        long beginning = epoch - TimeUnit.HOURS.toMillis(720);
        long end = epoch - TimeUnit.HOURS.toMillis(72);

        List<String> unrefreshedCertDates = DynamoDBUtils.getISODatesByRange(beginning, end);
        assertEquals(unrefreshedCertDates.size(), 28);
        assertTrue(unrefreshedCertDates.contains("2020-05-10"));
        assertTrue(unrefreshedCertDates.contains("2020-06-06"));

        assertFalse(unrefreshedCertDates.contains("2020-05-09"));
        assertFalse(unrefreshedCertDates.contains("2020-06-07"));
    }
}

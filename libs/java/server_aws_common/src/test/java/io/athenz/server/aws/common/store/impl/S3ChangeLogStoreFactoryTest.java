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
package io.athenz.server.aws.common.store.impl;

import com.yahoo.athenz.common.server.store.ChangeLogStore;
import org.testng.annotations.Test;

import static com.yahoo.athenz.common.ServerCommonConsts.ZTS_PROP_AWS_BUCKET_NAME;
import static com.yahoo.athenz.common.ServerCommonConsts.ZTS_PROP_AWS_REGION_NAME;
import static org.testng.Assert.*;

public class S3ChangeLogStoreFactoryTest {

    @Test
    public void testCreateStore() {
        System.setProperty(ZTS_PROP_AWS_BUCKET_NAME, "s3-unit-test-bucket-name");
        System.setProperty(ZTS_PROP_AWS_REGION_NAME, "us-west-1");

        S3ChangeLogStoreFactory factory = new S3ChangeLogStoreFactory();
        ChangeLogStore store = factory.create(null, null, null);
        assertNotNull(store);

        System.clearProperty(ZTS_PROP_AWS_BUCKET_NAME);
        System.clearProperty(ZTS_PROP_AWS_REGION_NAME);
    }
}

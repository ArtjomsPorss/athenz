/*
 * Copyright The Athenz Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yahoo.athenz.common.server.notification;

import com.yahoo.athenz.common.server.ServerResourceException;
import com.yahoo.athenz.common.server.db.DomainProvider;

public interface NotificationService {

    /**
     * send out the notification
     * @param notification - notification to be sent containing notification type, recipients and additional details
     * @return status of sent notification
     */
    boolean notify(Notification notification) throws ServerResourceException;

    /**
     * Set the domain provider for the notification service.
     * This API is called when the notification service is created.
     * @param domainProvider - domain object provider
     */
    default void setDomainProvider(DomainProvider domainProvider) {
    }
}

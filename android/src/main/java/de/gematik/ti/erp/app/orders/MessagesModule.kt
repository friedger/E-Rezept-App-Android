/*
 * Copyright (c) 2022 gematik GmbH
 * 
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the Licence);
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 * 
 *     https://joinup.ec.europa.eu/software/page/eupl
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 * 
 */

package de.gematik.ti.erp.app.orders

import de.gematik.ti.erp.app.orders.repository.CommunicationLocalDataSource
import de.gematik.ti.erp.app.orders.repository.CommunicationRepository
import de.gematik.ti.erp.app.orders.repository.PharmacyCacheLocalDataSource
import de.gematik.ti.erp.app.orders.repository.PharmacyCacheRemoteDataSource
import de.gematik.ti.erp.app.orders.usecase.OrderUseCase
import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.instance

val messagesModule = DI.Module("messagesModule") {
    bindProvider { PharmacyCacheLocalDataSource(instance()) }
    bindProvider { PharmacyCacheRemoteDataSource(instance()) }
    bindProvider { CommunicationLocalDataSource(instance()) }
    bindProvider { CommunicationRepository(instance(), instance(), instance(), instance(), instance(), instance()) }
    bindProvider { OrderUseCase(instance(), instance()) }
}

/*
 * Copyright (c) 2021 gematik GmbH
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

package de.gematik.ti.erp.app.pharmacy.ui.model

import androidx.navigation.NavType
import androidx.navigation.compose.navArgument
import de.gematik.ti.erp.app.Route

object PharmacyNavigationScreens {
    object SearchResults : Route("SearchResults")
    object PharmacyDetails : Route("PharmacyDetails")
    object ReserveInPharmacy : Route("ReserveInPharmacy")
    object CourierDelivery : Route("CourierDelivery")
    object MailDelivery : Route("MailDelivery")
    object UploadStatus : Route("UploadStatus", navArgument("redeemOption") { type = NavType.IntType }) {
        fun path(redeemOption: Int) = path("redeemOption" to redeemOption)
    }
}
package pl.touk.krush.one2many.bidi

import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upperCase
import org.junit.jupiter.api.Test
import pl.touk.krush.base.BaseDatabaseTest

class CustomerTest : BaseDatabaseTest() {

    @Test
    fun shouldInsertAndSelectBidiOneToMany() {
        transaction {
            SchemaUtils.create(CustomerTable, PhoneTable, AddressTable)

            // given
            val currentAddress = Address(city = "Warsaw", street = "Suwak", houseNo = "12/14", apartmentNo = "206")

            val customer = CustomerTable.insert(Customer(name = "TouK", age = 13, currentAddress = currentAddress)).copy(currentAddress = null)

            val phone = PhoneTable.insert(Phone(number = "777 888 999", customer = customer))
            val address = AddressTable.insert(currentAddress.copy(customer = customer))

            // then
            val customers = (CustomerTable leftJoin PhoneTable leftJoin AddressTable)
                    .select { PhoneTable.number.isNotNull() and (AddressTable.street.upperCase() eq "SUWAK")}
                    .toCustomerList()

            // then
            val fullCustomer = customer.copy(addresses = listOf(address), phones = listOf(phone))
            assertThat(customers).containsOnly(fullCustomer)
        }
    }
}

package net.corda.membership.impl.registration.dynamic.handler.mgm

import net.corda.avro.serialization.CordaAvroSerializationFactory
import net.corda.data.membership.command.registration.RegistrationCommand
import net.corda.data.membership.command.registration.mgm.CheckForPendingRegistration
import net.corda.data.membership.command.registration.mgm.DeclineRegistration
import net.corda.data.membership.common.v2.RegistrationStatus
import net.corda.data.membership.state.RegistrationState
import net.corda.data.p2p.app.MembershipStatusFilter
import net.corda.libs.configuration.SmartConfig
import net.corda.membership.impl.registration.dynamic.handler.MemberTypeChecker
import net.corda.membership.impl.registration.dynamic.handler.MissingRegistrationStateException
import net.corda.membership.impl.registration.dynamic.handler.RegistrationHandler
import net.corda.membership.impl.registration.dynamic.handler.RegistrationHandlerResult
import net.corda.membership.lib.VersionedMessageBuilder.retrieveRegistrationStatusMessage
import net.corda.membership.p2p.helpers.P2pRecordsFactory
import net.corda.membership.p2p.helpers.P2pRecordsFactory.Companion.getTtlMinutes
import net.corda.membership.persistence.client.MembershipPersistenceClient
import net.corda.membership.read.MembershipGroupReaderProvider
import net.corda.messaging.api.records.Record
import net.corda.schema.Schemas.Membership.REGISTRATION_COMMAND_TOPIC
import net.corda.schema.configuration.MembershipConfig.TtlsConfig.DECLINE_REGISTRATION
import net.corda.utilities.time.Clock
import net.corda.v5.base.exceptions.CordaRuntimeException
import net.corda.virtualnode.toCorda
import org.slf4j.LoggerFactory

@Suppress("LongParameterList")
internal class DeclineRegistrationHandler(
    private val membershipPersistenceClient: MembershipPersistenceClient,
    clock: Clock,
    cordaAvroSerializationFactory: CordaAvroSerializationFactory,
    private val memberTypeChecker: MemberTypeChecker,
    private val membershipConfig: SmartConfig,
    private val groupReaderProvider: MembershipGroupReaderProvider,
    private val p2pRecordsFactory: P2pRecordsFactory = P2pRecordsFactory(
        cordaAvroSerializationFactory,
        clock,
    ),
) : RegistrationHandler<DeclineRegistration> {
    private companion object {
        val logger = LoggerFactory.getLogger(this::class.java.enclosingClass)
    }
    override fun invoke(
        state: RegistrationState?,
        key: String,
        command: DeclineRegistration
    ): RegistrationHandlerResult {
        if (state == null) throw MissingRegistrationStateException
        // Update the state of the request and member
        val declinedBy = state.mgm
        val declinedMember = state.registeringMember
        val registrationId = state.registrationId
        if (memberTypeChecker.isMgm(declinedMember)) {
            logger.warn("Trying to decline registration request: '$registrationId' of ${declinedMember.x500Name} which is an MGM")
        }
        if (!memberTypeChecker.isMgm(declinedBy)) {
            logger.warn("Trying to decline registration request: '$registrationId' by ${declinedBy.x500Name} which is not an MGM")
        }
        val memberInfo = groupReaderProvider.getGroupReader(declinedBy.toCorda())
            .lookup(declinedMember.toCorda().x500Name, MembershipStatusFilter.PENDING)
            ?: throw CordaRuntimeException("Failed to retrieve pending member's info " +
                    "for member with holding ID'${declinedMember.toCorda().shortHash}'.")
        logger.info("Declining registration request: '$registrationId' for ${declinedMember.x500Name} - ${command.reason}")
        val registrationRequestDeclinedCommand = membershipPersistenceClient.setRegistrationRequestStatus(
            viewOwningIdentity = declinedBy.toCorda(),
            registrationId = registrationId,
            registrationRequestStatus = RegistrationStatus.DECLINED,
            reason = command.reason
        ).createAsyncCommands()
        val statusUpdateMessage = retrieveRegistrationStatusMessage(
            memberInfo.platformVersion,
            registrationId,
            RegistrationStatus.DECLINED.name,
        )
        val memberDeclinedMessage = if (statusUpdateMessage != null) {
            p2pRecordsFactory.createAuthenticatedMessageRecord(
                source = declinedBy,
                destination = declinedMember,
                // Setting TTL to avoid resending the message in case the decline reason is that the
                // P2P channel could not be established.
                minutesToWait = membershipConfig.getTtlMinutes(DECLINE_REGISTRATION),
                content = statusUpdateMessage,
                filter = MembershipStatusFilter.PENDING,
            )
        } else { null }
        val commandToStartProcessingTheNextRequest = Record(
            topic = REGISTRATION_COMMAND_TOPIC,
            key = key,
            value = RegistrationCommand(CheckForPendingRegistration(declinedBy, declinedMember, 0))
        )
        return RegistrationHandlerResult(
            null,
            listOfNotNull(memberDeclinedMessage, commandToStartProcessingTheNextRequest) + registrationRequestDeclinedCommand
        )
    }

    override val commandType: Class<DeclineRegistration>
        get() = DeclineRegistration::class.java

    override fun getOwnerHoldingId(
        state: RegistrationState?,
        command: DeclineRegistration
    ) = state?.mgm
}

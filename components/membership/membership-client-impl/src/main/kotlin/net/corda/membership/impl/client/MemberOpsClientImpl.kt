package net.corda.membership.impl.client

import net.corda.configuration.read.ConfigurationReadService
import net.corda.data.membership.rpc.request.MembershipRpcRequest
import net.corda.data.membership.rpc.request.MembershipRpcRequestContext
import net.corda.data.membership.rpc.request.RegistrationAction
import net.corda.data.membership.rpc.request.RegistrationRequest
import net.corda.data.membership.rpc.request.RegistrationStatusRequest
import net.corda.data.membership.rpc.response.MembershipRpcResponse
import net.corda.data.membership.rpc.response.RegistrationResponse
import net.corda.lifecycle.LifecycleCoordinatorFactory
import net.corda.lifecycle.LifecycleStatus
import net.corda.lifecycle.createCoordinator
import net.corda.membership.client.MemberOpsClient
import net.corda.membership.client.dto.MemberInfoSubmittedDto
import net.corda.membership.client.dto.MemberRegistrationRequestDto
import net.corda.membership.client.dto.RegistrationRequestProgressDto
import net.corda.membership.impl.client.lifecycle.MemberOpsClientLifecycleHandler
import net.corda.messaging.api.publisher.RPCSender
import net.corda.messaging.api.publisher.factory.PublisherFactory
import net.corda.v5.base.concurrent.getOrThrow
import net.corda.v5.base.exceptions.CordaRuntimeException
import net.corda.v5.base.util.contextLogger
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.Reference
import org.slf4j.Logger
import java.time.Instant
import java.util.UUID

@Component(service = [MemberOpsClient::class])
class MemberOpsClientImpl @Activate constructor(
    @Reference(service = LifecycleCoordinatorFactory::class)
    val coordinatorFactory: LifecycleCoordinatorFactory,
    @Reference(service = PublisherFactory::class)
    val publisherFactory: PublisherFactory,
    @Reference(service = ConfigurationReadService::class)
    val configurationReadService: ConfigurationReadService
) : MemberOpsClient {
    companion object {
        private val logger: Logger = contextLogger()
    }

    private interface InnerMemberOpsClient : AutoCloseable {
        fun startRegistration(memberRegistrationRequest: MemberRegistrationRequestDto): RegistrationRequestProgressDto

        fun checkRegistrationProgress(holdingIdentityId: String): RegistrationRequestProgressDto
    }

    private var impl: InnerMemberOpsClient = InactiveImpl()

    private val lifecycleHandler = MemberOpsClientLifecycleHandler(
        publisherFactory,
        configurationReadService,
        ::activate,
        ::deactivate
    )

    private val coordinator = coordinatorFactory.createCoordinator<MemberOpsClient>(lifecycleHandler)

    private val className = this::class.java.simpleName

    override val isRunning: Boolean
        get() = coordinator.isRunning

    override fun start() {
        logger.info("$className started.")
        coordinator.start()
    }

    override fun stop() {
        logger.info("$className stopped.")
        coordinator.stop()
    }

    private fun activate(rpcSender: RPCSender<MembershipRpcRequest, MembershipRpcResponse>, reason: String) {
        implSwap(ActiveImpl(rpcSender))
        updateStatus(LifecycleStatus.UP, reason)
    }

    private fun deactivate(reason: String) {
        updateStatus(LifecycleStatus.DOWN, reason)
        implSwap(InactiveImpl())
    }

    private fun implSwap(newImpl: InnerMemberOpsClient) {
        val current = impl
        impl = newImpl
        current.close()
    }

    private fun updateStatus(status: LifecycleStatus, reason: String){
        if(coordinator.status != status) {
            coordinator.updateStatus(status, reason)
        }
    }

    override fun startRegistration(memberRegistrationRequest: MemberRegistrationRequestDto) =
        impl.startRegistration(memberRegistrationRequest)

    override fun checkRegistrationProgress(holdingIdentityId: String) =
        impl.checkRegistrationProgress(holdingIdentityId)

    private class InactiveImpl : InnerMemberOpsClient {
        companion object {
            const val ERROR_MSG = "Service is in an incorrect state for calling."
        }

        override fun startRegistration(memberRegistrationRequest: MemberRegistrationRequestDto) =
            throw IllegalStateException(ERROR_MSG)

        override fun checkRegistrationProgress(holdingIdentityId: String) =
            throw IllegalStateException(ERROR_MSG)

        override fun close() = Unit

    }

    private class ActiveImpl(
        val rpcSender: RPCSender<MembershipRpcRequest, MembershipRpcResponse>
    ) : InnerMemberOpsClient {
        override fun startRegistration(memberRegistrationRequest: MemberRegistrationRequestDto): RegistrationRequestProgressDto {
            val request = MembershipRpcRequest(
                MembershipRpcRequestContext(
                    UUID.randomUUID().toString(),
                    Instant.now()
                ),
                RegistrationRequest(
                    memberRegistrationRequest.holdingIdentityId,
                    RegistrationAction.valueOf(memberRegistrationRequest.action.name)
                )
            )

            return registrationResponse(request.sendRequest())
        }

        override fun checkRegistrationProgress(holdingIdentityId: String): RegistrationRequestProgressDto {
            val request = MembershipRpcRequest(
                MembershipRpcRequestContext(
                    UUID.randomUUID().toString(),
                    Instant.now()
                ),
                RegistrationStatusRequest(holdingIdentityId)
            )

            return registrationResponse(request.sendRequest())
        }

        override fun close() = rpcSender.close()

        @Suppress("SpreadOperator")
        private fun registrationResponse(response: RegistrationResponse): RegistrationRequestProgressDto =
            RegistrationRequestProgressDto(
                response.registrationSent,
                response.registrationStatus.toString(),
                MemberInfoSubmittedDto(
                    mapOf(
                        "registrationProtocolVersion" to response.registrationProtocolVersion.toString(),
                        *response.memberProvidedContext.items.map { it.key to it.value }.toTypedArray(),
                        *response.additionalInfo.items.map { it.key to it.value }.toTypedArray()
                    )
                )
            )

        @Suppress("UNCHECKED_CAST")
        private inline fun <reified RESPONSE> MembershipRpcRequest.sendRequest(): RESPONSE {
            try {
                logger.info("Sending request: $this")
                val response = rpcSender.sendRequest(this).getOrThrow()
                require(response != null && response.responseContext != null && response.response != null) {
                    "Response cannot be null."
                }
                require(this.requestContext.requestId == response.responseContext.requestId) {
                    "Request ID must match in the request and response."
                }
                require(this.requestContext.requestTimestamp == response.responseContext.requestTimestamp) {
                    "Request timestamp must match in the request and response."
                }
                require(response.response is RESPONSE) {
                    "Expected ${RESPONSE::class.java} as response type, but received ${response.response.javaClass}."
                }

                return response.response as RESPONSE
            } catch (e: Exception) {
                throw CordaRuntimeException(
                    "Failed to send request and receive response for membership RPC operation. " + e.message, e
                )
            }
        }
    }
}
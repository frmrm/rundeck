package rundeck.services.audit

import com.dtolabs.rundeck.core.audit.ActionTypes
import com.dtolabs.rundeck.core.audit.AuditEvent
import com.dtolabs.rundeck.core.audit.ResourceTypes
import com.dtolabs.rundeck.plugins.audit.AuditEventListener
import grails.testing.services.ServiceUnitTest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import rundeck.services.FrameworkService
import spock.lang.Specification
import spock.lang.Unroll

import java.security.Principal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AuditEventsServiceSpec extends Specification implements ServiceUnitTest<AuditEventsService> {

    @Unroll
    def "test extract username"() {
        when:
        def result = AuditEventsService.extractUsername(auth)

        then:
        expect == result

        where:
        expect  | auth
        null    | null
        null    | new UsernamePasswordAuthenticationToken(null,null)
        ""      | new UsernamePasswordAuthenticationToken("",null)
        "admin" | new UsernamePasswordAuthenticationToken("admin",null)
        "admin" | new UsernamePasswordAuthenticationToken(new Principal() {
            @Override
            String getName() {
                return "admin"
            }
        }, null)
    }

    def "Test event builder data cloning"() {
        given:
        def user = "MockUsername"
        def roleList = ["admin", "user", "test"]
        def uuid = "11111111-1111-0000-0000-111111111111"
        def frameMock = Mock(FrameworkService) {
            getServerUUID() >> {uuid}
            getServerHostname() >> { "server" }
        }

        when:
        service.frameworkService = frameMock
        def builder = service.eventBuilder()
                .setUsername(user)
                .setUserRoles(roleList)
                .setActionType(ActionTypes.LOGIN_SUCCESS)
                .setResourceType(ResourceTypes.USER)
                .setResourceName(user)

        def event1 = builder.build()
        def event2 = builder.build()

        then:
        !event1.is(event2)
        event1.userInfo.username == user
        event2.userInfo.username == user
        !event1.userInfo.userRoles.is(event2.userInfo.userRoles)
        event1.userInfo.userRoles == roleList
        event2.userInfo.userRoles == roleList
        event1.requestInfo.serverUUID == uuid
        event2.requestInfo.serverUUID == uuid

    }

    def "Test dispatching through executor"() {
        def receivedEvent = null
        def invocations = 0

        given:
        def user = "MockUsername"
        def roleList = ["admin", "user", "test"]
        def uuid = "11111111-1111-0000-0000-111111111111"
        def frameMock = Mock(FrameworkService) {
            getServerUUID() >> {uuid}
            getServerHostname() >> { "server" }
        }
        service.frameworkService = frameMock
        // Disable search of installed plugins.
        service.installedPlugins = new HashMap<>()

            CountDownLatch latch=new CountDownLatch(1)
        when:
        service.addListener(new AuditEventListener() {
            @Override
            void onEvent(AuditEvent event) {
                receivedEvent = event
                invocations++
                latch.countDown()
            }
        })
        service.eventBuilder()
                .setUsername(user)
                .setUserRoles(roleList)
                .setActionType(ActionTypes.LOGIN_SUCCESS)
                .setResourceType(ResourceTypes.USER)
                .setResourceName(user)
                .publish()
        // Wait for async process to finish
        latch.await(3, TimeUnit.SECONDS)

        then:
        invocations == 1
        receivedEvent instanceof AuditEvent
        receivedEvent.userInfo.username == user
        !receivedEvent.userInfo.userRoles.is(roleList)
        receivedEvent.userInfo.userRoles == roleList
        receivedEvent.requestInfo.serverUUID == uuid
    }


}

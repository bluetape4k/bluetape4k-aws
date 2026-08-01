package io.bluetape4k.aws.kotlin.http

import aws.smithy.kotlin.runtime.http.engine.crt.CrtHttpEngine
import aws.smithy.kotlin.runtime.http.engine.okhttp.OkHttpEngine

/**
 * AWS SDK for Kotlin 클라이언트가 재사용할 수 있는 싱글턴 HTTP 엔진을 제공합니다.
 *
 * ## 소유권 계약
 *
 * 이 공급자의 엔진은 공유되며 외부에서 관리합니다. 여러 클라이언트가 의도적으로 같은 전송 계층을
 * 공유할 때만 클라이언트에 전달하세요. SDK는 명시적으로 제공된 엔진을 `isManaged=false`로
 * 취급하므로 `client.close()`는 엔진을 닫지 않습니다.
 *
 * 단일 클라이언트에서 사용할 때는 `httpClient` 파라미터를 생략해 SDK가 엔진 수명 주기를
 * 소유하게 하거나 `withXxxClient { }` 도우미를 사용하세요.
 */
object HttpClientEngineProvider {

    /**
     * AWS CRT HTTP 엔진용 싱글턴 공급자입니다.
     *
     * CRT 엔진은 데몬이 아닌 스레드를 사용합니다. 이 엔진을 공유하는 모든 클라이언트를 닫은 뒤
     * 애플리케이션 종료 시 엔진을 명시적으로 닫으세요.
     */
    object Crt {
        @JvmStatic
        val httpEngine: CrtHttpEngine by lazy { crtHttpEngineOf() }
    }

    /**
     * OkHttp HTTP 엔진용 싱글턴 공급자입니다.
     */
    object OkHttp {
        @JvmStatic
        val httpEngine: OkHttpEngine by lazy { okHttpEngineOf() }
    }

    /**
     * 외부 엔진 소유권을 명시적으로 선택한 호출자를 위한 공유 CRT HTTP 엔진입니다.
     */
    val defaultHttpEngine get() = Crt.httpEngine
}

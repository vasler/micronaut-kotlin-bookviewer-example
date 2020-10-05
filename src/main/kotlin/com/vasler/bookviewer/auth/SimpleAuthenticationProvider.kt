package com.vasler.bookviewer.auth

import io.micronaut.http.HttpRequest
import io.micronaut.security.authentication.*
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.FlowableEmitter
import org.reactivestreams.Publisher
import javax.inject.Singleton

@Singleton
class SimpleAuthenticationProvider : AuthenticationProvider {
    override fun authenticate(httpRequest: HttpRequest<*>?, authenticationRequest: AuthenticationRequest<*, *>?): Publisher<AuthenticationResponse> {
        return Flowable.create({ emitter: FlowableEmitter<AuthenticationResponse> ->

            // LET EVERYONE IN
            emitter.onNext(UserDetails(authenticationRequest?.identity as String, ArrayList()))
            emitter.onComplete()

//            if (authenticationRequest?.identity == "username" && authenticationRequest.secret == "password") {
//                emitter.onNext(UserDetails(authenticationRequest?.identity as String, ArrayList()))
//                emitter.onComplete()
//            } else {
//                emitter.onError(AuthenticationException(AuthenticationFailed()))
//            }
        }, BackpressureStrategy.ERROR)
    }
}


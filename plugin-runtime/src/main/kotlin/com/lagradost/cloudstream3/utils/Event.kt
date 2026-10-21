package com.lagradost.cloudstream3.utils

/**
 * Headless port of the app-module Event holder (upstream
 * app/src/main/java/com/lagradost/cloudstream3/utils/Event.kt) — community
 * plugins observe/call lifecycle events like MainActivity.reloadHomeEvent.
 * Headless it is simply an in-process observer set.
 */
class Event<T> {
    private val observers = mutableSetOf<(T) -> Unit>()

    val size: Int get() = observers.size

    operator fun plusAssign(observer: (T) -> Unit) {
        synchronized(observers) {
            observers.add(observer)
        }
    }

    operator fun minusAssign(observer: (T) -> Unit) {
        synchronized(observers) {
            observers.remove(observer)
        }
    }

    operator fun invoke(value: T) {
        synchronized(observers) {
            for (observer in observers)
                observer(value)
        }
    }
}

class EmptyEvent {
    private val observers = mutableSetOf<Runnable>()

    val size: Int get() = observers.size

    operator fun plusAssign(observer: Runnable) {
        synchronized(observers) {
            observers.add(observer)
        }
    }

    operator fun minusAssign(observer: Runnable) {
        synchronized(observers) {
            observers.remove(observer)
        }
    }

    operator fun invoke() {
        synchronized(observers) {
            for (observer in observers)
                observer.run()
        }
    }
}

package tw.geothings.rekotlin

/**
 * Created by Taras Vozniuk on 31/07/2017.
 * Copyright © 2017 GeoThings. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

class Store<State: StateType>(
        private val reducer: Reducer<State>,
        state: State?,
        middleware: List<Middleware<State>> = emptyList(),
        automaticallySkipRepeats: Boolean = true) {

    private var _state: State? = state
        set(value) {
            val oldValue = field
            field = value

            value?.let {
                subscriptions.forEach {
                    it.newValues(oldValue, value)
                }
            }
        }

    val state: State
        get() { return _state!! }

    @Suppress("NAME_SHADOWING")
    var dispatchFunction: DispatchFunction = middleware
            .reversed()
            .fold({ action: Action -> this._defaultDispatch(action) }, { dispatchFunction, middleware ->
                val dispatch = { action: Action -> this.dispatch(action) }
                val getState = { this._state }
                middleware(dispatch, getState)(dispatchFunction)
            })

    val subscriptions: MutableList<SubscriptionBox<State, Any>> = mutableListOf()

    private var isDispatching = false

    /**
     * Indicates if new subscriptions attempt to apply `skipRepeats` by default.
     */
    val subscribersAutomaticallySkipsRepeat: Boolean = automaticallySkipRepeats

    init {
        this._state?.let { this._state = state } ?: this.dispatch(ReKotlinInit())
    }

    fun <SelectedState, S: StoreSubscriber<SelectedState>> subscribe(subscriber: S) {

        // if subscribersAutomaticallySkipsRepeat is set
        // skipRepeats will be applied with kotlin structural equality
        if (subscribersAutomaticallySkipsRepeat){
            this.subscribe(subscriber, {
                @Suppress("UNCHECKED_CAST")
                it.skipRepeats() as Subscription<SelectedState>
            })
        } else {
            this.subscribe(subscriber, null)
        }
    }

    fun <SelectedState, S: StoreSubscriber<SelectedState>> subscribe(subscriber: S, transform: ((Subscription<State>) -> Subscription<SelectedState>)?) {
        // If the same subscriber is already registered with the store, replace the existing
        // subscription with the new one.
        val index = this.subscriptions.indexOfFirst { it.subscriber === subscriber }
        if (index != -1){
            this.subscriptions.removeAt(index)
        }

        // Create a subscription for the new subscriber.
        val originalSubscription = Subscription<State>()
        // Call the optional transformation closure. This allows callers to modify
        // the subscription, e.g. in order to subselect parts of the store's state.
        val transformedSubscription = transform?.invoke(originalSubscription)

        val subscriptionBox = SubscriptionBox(originalSubscription, transformedSubscription, subscriber)

        // each subscriber has its own potentially different SelectedState that doesn't have to conform to StateType
        @Suppress("UNCHECKED_CAST")
        this.subscriptions.add(subscriptionBox as SubscriptionBox<State, Any>)

        this._state?.let {
            originalSubscription.newValues(null, it)
        }
    }

    fun <SelectedState> unsubscribe(subscriber: StoreSubscriber<SelectedState>) where SelectedState: StateType {
        val index = this.subscriptions.indexOfFirst { it.subscriber === subscriber }
        if (index != -1){
            this.subscriptions.removeAt(index)
        }
    }

    fun _defaultDispatch(action: Action){
        if (isDispatching) {
            throw Exception(
                    "ReKotlin:ConcurrentMutationError- Action has been dispatched while" +
                    " a previous action is action is being processed. A reducer" +
                    " is dispatching an action, or ReSwift is used in a concurrent context" +
                    " (e.g. from multiple threads)."
            )
        }

        this.isDispatching = true
        val newState = reducer(action, this._state)
        this.isDispatching = false

        this._state = newState
    }

    fun dispatch(action: Action){
        this.dispatchFunction(action)
    }

    fun dispatch(actionCreatorProvider: ActionCreator<State, Store<State>>){
        actionCreatorProvider(this.state, this)?.let {
            this.dispatch(it)
        }
    }

    fun dispatch(asynchActionCreatorProvider: AsynchActionCreator<State, Store<State>>){
        this.dispatch(asynchActionCreatorProvider, null)
    }

    fun dispatch(asynchActionCreatorProvider: AsynchActionCreator<State, Store<State>>, callback: DispatchCallback<State>?){
        asynchActionCreatorProvider(this.state, this) { actionProvider ->
            val action = actionProvider(this.state, this)

            action?.let {
                this.dispatch(it)
                callback?.invoke(this.state)
            }
        }
    }
}

typealias DispatchCallback<State> = (State) -> Unit

typealias ActionCreator<State, Store> = (state: State, store: Store) -> Action?

typealias AsynchActionCreator<State, Store> = (state: State, store: Store, actionCreatorCallback: (ActionCreator<State, Store>) -> Unit) -> Unit
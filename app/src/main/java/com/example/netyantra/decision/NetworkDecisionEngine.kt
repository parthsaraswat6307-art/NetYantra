package com.example.netyantra.decision

import com.example.netyantra.monitor.ConnectivityState

/**
 * Evaluator contract for making intelligent interface routing decisions.
 */
interface NetworkDecisionEngine {
    fun evaluate(state: ConnectivityState, preference: RoutingPreference): DecisionResult
}

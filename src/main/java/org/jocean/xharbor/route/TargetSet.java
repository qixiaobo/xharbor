/**
 * 
 */
package org.jocean.xharbor.route;

import java.net.URI;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author isdom
 *
 */
public class TargetSet {

    private static final int MAX_EFFECTIVEWEIGHT = 1000;
    
    public TargetSet(final URI[] uris, final URISMemo urisMemo) {
        this._urisMemo = urisMemo;
        this._targets = new ArrayList<Target>() {
            private static final long serialVersionUID = 1L;
        {
            for ( URI uri : uris) {
                this.add(new Target(uri));
            }
        }}.toArray(new Target[0]);
    }
    
    public String[] getStatus() {
        return new ArrayList<String>() {
            private static final long serialVersionUID = 1L;
        {
            for (Target peer : _targets) {
                this.add(peer._uri.toString() + ":down(" + isTargetDown(peer) 
                        + "):effectiveWeight(" + peer._effectiveWeight.get()
                        + "):currentWeight(" + peer._currentWeight.get()
                        + ")"
                        );
            }
        }}.toArray(new String[0]);
    }
    
    public URI selectTarget() {
        int total = 0;
        Target best = null;
        for ( Target peer : this._targets ) {
            if ( !isTargetDown(peer) ) {
                // peer->current_weight += peer->effective_weight; 
                final int effectiveWeight = peer._effectiveWeight.get();
                final int currentWeight = peer._currentWeight.addAndGet( effectiveWeight );
                total += effectiveWeight;
                
//                if (best == NULL || peer->current_weight > best->current_weight) {
//                    best = peer;
//                }
                if ( null == best || best._currentWeight.get() < currentWeight ) {
                    best = peer;
                }
            }
        }
        
        if (null == best) {
            return null;
        }
        
//        best->current_weight -= total;
        best._currentWeight.addAndGet(-total);
        
        return best._uri;
    }

    public void updateWeight(final URI uri, final int deltaWeight) {
        final Target target = uri2target(uri);
        if (null != target) {
            if ( target._effectiveWeight.addAndGet(deltaWeight) > MAX_EFFECTIVEWEIGHT ) {
                target._effectiveWeight.addAndGet(-deltaWeight);
            }
        }
    }
    
    /**
     * @param peer
     * @return
     */
    private boolean isTargetDown(Target peer) {
        return this._urisMemo.isDown(peer._uri); // _down.get();
    }
    
    public void markTargetDown(final URI uri) {
        this._urisMemo.markDownStatus(uri, true);
//        final Target target = uri2target(uri);
//        if (null != target) {
//            target._down.set(true);
//        }
    }
    
    private Target uri2target(final URI uri) {
        for ( Target peer : this._targets ) {
            if ( peer._uri.equals(uri)) {
                return peer;
            }
        }
        return null;
    }

    private static class Target {
        Target(final URI uri) {
            this._uri = uri;
        }
        
        public final URI _uri;
        private final AtomicInteger _currentWeight = new AtomicInteger(1);
        private final AtomicInteger _effectiveWeight = new AtomicInteger(1);
//        private final AtomicBoolean _down = new AtomicBoolean(false);
    }
    
    private final Target[] _targets;
    private final URISMemo _urisMemo;
}
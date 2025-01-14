package com.andzhv.githubusers.ui.base

import com.adgvcxz.IModel
import com.adgvcxz.IMutation
import com.adgvcxz.recyclerviewmodel.*
import com.andzhv.githubusers.Config
import com.andzhv.githubusers.utils.ex.just
import io.reactivex.rxjava3.annotations.NonNull
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single

/**
 * Created by zhaowei on 2021/9/10.
 */
abstract class BaseListViewModel<M>(hasLoadMore: Boolean) : RecyclerViewModel() {

    final override val initModel: RecyclerModel = RecyclerModel(emptyList(), hasLoadMore, false)

    val items: List<RecyclerItemViewModel<out IModel>> get() = currentModel().items

    /**
     * Show cache only the first time
     */
    var showCacheOnlyFirstRequest = true

    private var cacheShowed = false

    /**
     * Detect duplicate data
     */
    var detectDuplicateData = false

    private val duplicateSet = hashSetOf<M>()


    open fun getCache(): List<M> {
        return emptyList()
    }

    open fun requestCache(refresh: Boolean): Observable<IMutation> {
        return if (refresh && items.isEmpty()) {
            if (refresh) duplicateSet.clear()
            cacheShowed = true
            var position = 0
            val headerSingle = if (refresh) {
                header(false).doOnNext { position += 1 }.toList()
            } else {
                Single.just(emptyList())
            }
            val cacheSingle = Observable.fromIterable(checkDuplicateData(getCache()))
                .flatMap { item -> transform(position, item).doOnNext { position += 1 } }
                .toList()
            return headerSingle.flatMapObservable { headerList ->
                cacheSingle.flatMapObservable { (headerList + it).just() }
            }.filter { it.isNotEmpty() }.map(::SetData).flatMap {
                Observable.just(SetRefresh(true), it, RemoveLoadingItem)
            }.onErrorResumeWith(Observable.empty())
        } else {
            Observable.empty()
        }
    }

    override fun request(refresh: Boolean): Observable<IMutation> {
        return if (!(showCacheOnlyFirstRequest && cacheShowed) && refresh && items.isEmpty()) {
            //For the first request, first display the initialized data, then request the data
            Observable.concat(requestCache(refresh), requestList(refresh))
        } else {
            requestList(refresh)
        }
    }

    private fun requestList(refresh: Boolean): @NonNull Observable<IMutation> {
        if (refresh) duplicateSet.clear()
        var position = if (refresh) 0 else items.filterNot { it is LoadingItemViewModel }.count()

        //Header List
        val headerSingle = if (refresh) {
            header(false).doOnNext { position += 1 }.toList()
        } else {
            Single.just(emptyList())
        }

        //Requested list
        var listSize = 0
        val listSingle = getList(refresh).concatMap {
            listSize = it.size
            Observable.fromIterable(checkDuplicateData(it)).flatMap { item ->
                transform(position, item).doOnNext { position += 1 }
            }
        }.toList()

        return headerSingle.flatMapObservable { headerList ->
            listSingle.flatMapObservable { list ->
                if (listSize < listLimit()) {
                    //If the length of the List is less than limit, delete the last LoadingItem
                    Observable.just(
                        UpdateData(headerList + list),
                        RemoveLoadingItem
                    )
                } else {
                    UpdateData(headerList + list).just()
                }
            }
        }.onErrorResumeWith(if (refresh) Observable.empty() else LoadFailure.just())
    }

    /**
     * Convert or delete events
     * Event(LoadMore) don't need to be embittered, the item has been processed
     */
    override fun transformMutation(mutation: Observable<IMutation>): Observable<IMutation> {
        return super.transformMutation(mutation).filter { it !is LoadFailure }
    }

    /**
     * Send a request for List
     * @param refresh if true: refresh else load more
     */
    abstract fun getList(refresh: Boolean): Observable<List<M>>

    /**
     * Convert Model to ViewModel
     * @return Observable
     * return list: Observable.concat(A, B, C)
     * return empty: Observable.empty()
     */
    abstract fun transform(position: Int, model: M): Observable<RecyclerItemViewModel<out IModel>>

    /**
     * Add a custom ViewModel to the head of the list
     * @param cache Whether the current list comes from the cache
     */
    open fun header(cache: Boolean): Observable<RecyclerItemViewModel<out IModel>> {
        return Observable.empty()
    }

    /**
     * List length per request
     */
    open fun listLimit(): Int {
        return Config.LIST_LIMIT
    }

    open fun checkDuplicateData(list: List<M>): List<M> {
        if (detectDuplicateData) {
            return list.filter { duplicateSet.add(it) }
        }
        return list
    }
}
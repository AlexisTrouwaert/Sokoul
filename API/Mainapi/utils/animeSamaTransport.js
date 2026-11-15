'use strict';

/** A transport failure is different from a legitimate empty catalogue or a refusal. */
async function requestWithDirectFallback(cycle, direct) {
    let response;
    try {
        response = await cycle();
    } catch (error) {
        const status = Number(error.response?.status || 0);
        if (status > 0 && status < 500) throw error;
    }
    if (response && Number(response.status) > 0 && Number(response.status) < 500) return response;
    try {
        const fallback = await direct();
        if (fallback && Number(fallback.status) > 0 && Number(fallback.status) < 500) return fallback;
    } catch (failure) {
        // A refusal or missing page from the direct request is an answer, not a transport failure.
        const status = Number(failure.response?.status || 0);
        if (status > 0 && status < 500) return failure.response;
    }
    const error = new Error('Anime-Sama catalogue transport unavailable');
    error.code = 'ANIME_CATALOGUE_UNAVAILABLE';
    throw error;
}

module.exports = { requestWithDirectFallback };

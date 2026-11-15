/** Select by provider season/episode numbers, never by their array positions. */
function selectOmegaEpisode(payload, season, episode) {
  if (!/^[1-9]\d{0,3}$/.test(String(season)) || !/^[1-9]\d{0,4}$/.test(String(episode))) return null;
  const languages = {};
  for (const series of payload?.series || []) {
    for (const candidate of series.seasons || []) {
      if (Number(candidate.number) !== Number(season)) continue;
      for (const entry of candidate.episodes || []) {
        if (Number(entry.number) !== Number(episode)) continue;
        for (const [language, version] of Object.entries(entry.versions || {})) {
          if (!Array.isArray(version?.players)) continue;
          const players = languages[language] ||= [];
          for (const player of version.players) {
            if (!player || typeof player !== 'object') continue;
            const url = player.link || player.decoded_url || player.url;
            if (typeof url === 'string' && !players.some(known => (known.link || known.decoded_url || known.url) === url)) {
              players.push({ ...player, language });
            }
          }
        }
      }
    }
  }
  return { season: Number(season), number: Number(episode), languages };
}
module.exports = { selectOmegaEpisode };

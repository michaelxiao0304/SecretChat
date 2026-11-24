export function formatWait(seconds) {
  if (seconds === null) return '—';
  if (seconds < 60) return seconds + ' 秒';
  return Math.floor(seconds / 60) + ' 分 ' + seconds % 60 + ' 秒';
}

export async function request(endpoint, options = {}, http = fetch) {
  const response = await http('../admin-api/' + endpoint, {credentials:'same-origin', ...options});
  if (!response.ok) {
    throw Object.assign(new Error('HTTP ' + response.status), {status:response.status});
  }
  if (response.status !== 204) return response.json();
}

export async function submitForm(endpoint, fields, http = fetch) {
  const csrf = await request('session', {}, http);
  return request(endpoint, {
    method:'POST',
    headers:{[csrf.header]:csrf.token},
    body:new URLSearchParams(fields)
  }, http);
}

export function formatWait(seconds) {
  if (seconds === null) return '—';
  if (seconds < 60) return seconds + ' 秒';
  return Math.floor(seconds / 60) + ' 分 ' + seconds % 60 + ' 秒';
}

export async function request(endpoint, options = {}, http = fetch) {
  const response = await http('../admin-api/' + endpoint, {credentials:'same-origin', ...options});
  if (!response.ok) {
    const error = Object.assign(new Error('HTTP ' + response.status), {status:response.status});
    const id = response.headers.get('X-Request-ID');
    if (/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/.test(id)) error.requestId = id;
    throw error;
  }
  if (response.status !== 204) return response.json();
}

export function failureMessage(message, error) {
  return error.requestId ? message + ' 请求编号：' + error.requestId : message;
}

export async function submitForm(endpoint, fields, http = fetch) {
  const csrf = await request('session', {}, http);
  return request(endpoint, {
    method:'POST',
    headers:{[csrf.header]:csrf.token},
    body:new URLSearchParams(fields)
  }, http);
}

export async function change(endpoint, method, data) {
  const csrf = await request('session');
  const options = {method, headers:{[csrf.header]:csrf.token}};
  if (data !== undefined) {
    options.headers['Content-Type'] = 'application/json';
    options.body = JSON.stringify(data);
  }
  return request(endpoint, options);
}

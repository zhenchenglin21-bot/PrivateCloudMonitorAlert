from __future__ import annotations


def escape_flux_string(value: str) -> str:
    return str(value or "").replace("\\", "\\\\").replace('"', '\\"')


def build_role_filter(role: str) -> str:
    current_role = str(role or "").strip().lower()
    if not current_role:
        return ""
    if current_role in {"server", "host"}:
        return '|> filter(fn: (r) => (not exists r.role) or r.role == "host" or r.role == "server")\n'
    return f'|> filter(fn: (r) => r.role == "{escape_flux_string(current_role)}")\n'


def build_target_filter(query_host: str, role: str, entity_name: str) -> str:
    escaped_query_host = escape_flux_string(query_host)
    escaped_entity_name = escape_flux_string(entity_name)
    current_role = str(role or "").strip().lower()

    if current_role == "vm":
        if not escaped_entity_name:
            return f'|> filter(fn: (r) => r.host == "{escaped_query_host}")\n'
        return (
            f'|> filter(fn: (r) => r.host == "{escaped_query_host}" '
            f'or (exists r.vm_name and r.vm_name == "{escaped_entity_name}") '
            f'or r.host == "{escaped_entity_name}")\n'
        )
    if current_role == "container":
        if not escaped_entity_name:
            return f'|> filter(fn: (r) => r.host == "{escaped_query_host}")\n'
        return (
            f'|> filter(fn: (r) => r.host == "{escaped_query_host}" '
            f'or (exists r.container_name and r.container_name == "{escaped_entity_name}") '
            f'or r.host == "{escaped_entity_name}")\n'
        )
    if not escaped_entity_name:
        return f'|> filter(fn: (r) => r.host == "{escaped_query_host}")\n'
    return (
        f'|> filter(fn: (r) => r.host == "{escaped_query_host}" '
        f'or (exists r.host_name and r.host_name == "{escaped_entity_name}") '
        f'or r.host == "{escaped_entity_name}")\n'
    )

package id.homebase.api.client

class ForbiddenException :
    OdinApiException(403, "Forbidden")
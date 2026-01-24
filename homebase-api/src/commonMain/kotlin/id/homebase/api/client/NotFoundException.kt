package id.homebase.api.client

class NotFoundException :
    OdinApiException(404, "Not found")